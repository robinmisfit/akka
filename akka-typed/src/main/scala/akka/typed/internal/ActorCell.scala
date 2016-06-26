/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.actor.InvalidActorNameException
import akka.util.Helpers
import scala.concurrent.duration.{ Duration, FiniteDuration }
import akka.dispatch.ExecutionContexts
import scala.concurrent.ExecutionContextExecutor
import akka.actor.Cancellable
import akka.util.Unsafe.{ instance ⇒ unsafe }
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.Queue
import scala.annotation.{ tailrec, switch }
import scala.util.control.NonFatal
import scala.util.control.Exception.Catcher
import akka.event.Logging.Error
import akka.event.Logging

/**
 * INTERNAL API
 */
private[typed] object ActorCell {
  /*
   * bit 0-20: activation count (number of (system)messages)
   * bit 21-30: suspend count
   * bit 31: isClosed
   *
   * Activation count is a bit special:
   * 0 means inactive
   * 1 means active without normal messages
   * N means active with N-1 normal messages
   */
  final val suspendShift = 21

  final val activationMask = (1 << suspendShift) - 1
  val maxActivations = activationMask - Runtime.getRuntime.availableProcessors

  final val suspendIncrement = 1 << suspendShift
  final val suspendMask = (1 << (31 - suspendShift)) - 1

  def isClosed(status: Int): Boolean = status < 0
  def isActive(status: Int): Boolean = (status & ~activationMask) == 0
  def isSuspended(status: Int): Boolean = ((status >> suspendShift) & suspendMask) != 0

  def activations(status: Int): Int = status & activationMask
  def messageCount(status: Int): Int = {
    val act = activations(status)
    if (act == 0) 0 else act - 1
  }

  val status = unsafe.objectFieldOffset(classOf[ActorCell[_]].getDeclaredField("_status"))
  val systemQueue = unsafe.objectFieldOffset(classOf[ActorCell[_]].getDeclaredField("_systemQueue"))

  final val DefaultState = 0
  final val SuspendedState = 1
  final val SuspendedWaitForChildrenState = 2
}

/**
 * INTERNAL API
 */
private[typed] class ActorCell[T](override val system: ActorSystemImpl[Nothing],
                                  override val props: Props[T],
                                  val parent: ActorRefImpl[Nothing])
  extends ActorContext[T] with Runnable with SupervisionMechanics[T] with DeathWatch[T] {

  /*
   * Implementation of the ActorContext trait.
   */

  protected var childrenMap = Map.empty[String, ActorRefImpl[Nothing]]
  protected var terminatingMap = Map.empty[String, ActorRefImpl[Nothing]]
  override def children: Iterable[ActorRef[Nothing]] = childrenMap.values
  override def child(name: String): Option[ActorRef[Nothing]] = childrenMap.get(name)

  private var _self: ActorRefImpl[T] = _
  private[typed] def setSelf(ref: ActorRefImpl[T]): Unit = _self = ref
  override def self: ActorRefImpl[T] = _self

  protected def ctx: ActorContext[T] = this

  override def spawn[U](props: Props[U], name: String): ActorRef[U] = {
    if (childrenMap contains name) throw new InvalidActorNameException(s"actor name [$name] is not unique")
    if (terminatingMap contains name) throw new InvalidActorNameException(s"actor name [$name] is not yet free")
    val cell = new ActorCell[U](system, props, self)
    val ref = new LocalActorRef[U](self.path / name, cell)
    cell.setSelf(ref)
    ref.sendSystem(Create())
    ref
  }

  private var nextName = 0L
  override def spawnAnonymous[U](props: Props[U]): ActorRef[U] = {
    val name = Helpers.base64(nextName)
    nextName += 1
    spawn(props, name)
  }

  override def stop(child: ActorRef[Nothing]): Boolean = {
    val name = child.path.name
    childrenMap get name match {
      case None                      ⇒ false
      case Some(ref) if ref != child ⇒ false
      case Some(ref) ⇒
        ref.sendSystem(Terminate())
        childrenMap -= name
        terminatingMap = terminatingMap.updated(name, ref)
        true
    }
  }

  protected def stopAll(): Unit = {
    childrenMap.valuesIterator.foreach { ref ⇒
      ref.sendSystem(Terminate())
      terminatingMap = terminatingMap.updated(ref.path.name, ref)
    }
    childrenMap = Map.empty
  }

  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): Cancellable =
    system.scheduler.scheduleOnce(delay)(target ! msg)(ExecutionContexts.sameThreadExecutionContext)

  override val executionContext: ExecutionContextExecutor = system.dispatchers.lookup(props.dispatcher)

  override def spawnAdapter[U](f: U ⇒ T): ActorRef[U] = ???

  override def setReceiveTimeout(d: Duration): Unit = ???

  /*
   * Implementation of the invocation mechanics.
   */
  import ActorCell._

  // see comment in companion object for details
  @volatile private[this] var _status: Int = 0
  protected def getStatus: Int = _status
  private[this] val queue: Queue[T] = new ConcurrentLinkedQueue[T]
  private[this] val maxQueue: Int = Math.min(props.queueSize, maxActivations)
  private[this] var _systemQueue: LatestFirstSystemMessageList = SystemMessageList.LNil

  protected def suspend(): Unit = unsafe.getAndAddInt(this, status, suspendIncrement)
  protected def resume(): Unit = unsafe.getAndAddInt(this, status, -suspendIncrement)

  private def handleException: Catcher[Unit] = {
    case e: InterruptedException ⇒
      system.eventStream.publish(Error(e, self.path.toString, getClass, "interrupted during message send"))
      Thread.currentThread.interrupt()
    case NonFatal(e) ⇒
      system.eventStream.publish(Error(e, self.path.toString, getClass, "swallowing exception during message send"))
  }

  def send(msg: T): Unit =
    try {
      val old = unsafe.getAndAddInt(this, status, 1)
      val oldActivations = activations(old)
      // this is not an off-by-one: #msgs is activations-1 if >0
      if (oldActivations > maxQueue) {
        // cannot enqueue, need to give back activation token
        unsafe.getAndAddInt(this, status, -1)
        system.eventStream.publish(Dropped(msg, self))
      } else if (isClosed(old)) {
        system.deadLetters ! msg
      } else {
        // need to enqueue; if the actor sees the token but not the message, it will reschedule
        queue.add(msg)
        if (oldActivations == 0 && isActive(old)) {
          unsafe.getAndAddInt(this, status, 1) // the first 1 was just the “active” bit, now add 1msg
          // if the actor was not yet running, set it in motion; spurious wakeups don’t hurt
          executionContext.execute(this)
        }
      }
    } catch handleException

  def sendSystem(signal: SystemMessage): Unit = {
    @tailrec def needToActivate(): Boolean = {
      val currentList = _systemQueue
      if (currentList.head == NoMessage) {
        system.deadLetters.sendSystem(signal)
        false
      } else {
        unsafe.compareAndSwapObject(this, systemQueue, currentList.head, (signal :: currentList).head) || {
          signal.unlink()
          needToActivate()
        }
      }
    }
    try {
      if (needToActivate()) {
        val old = unsafe.getAndAddInt(this, status, 1)
        if (isClosed(old)) {
          // nothing to do
        } else if (activations(old) == 0) {
          // all is good: we signaled the transition to active
          executionContext.execute(this)
        } else {
          // take back that token: we didn’t actually enqueue a normal message and the actor was already active
          unsafe.getAndAddInt(this, status, -1)
        }
      }
    } catch handleException
  }

  override final def run(): Unit = {
    val status = _status
    val msgs = messageCount(status)
    var processed = 0
    try {
      if (!isClosed(status)) {
        while (processAllSystemMessages() && !queue.isEmpty() && processed < msgs) {
          val msg = queue.poll()
          processed += 1
          processMessage(msg)
        }
      }
    } finally {
      val prev = unsafe.getAndAddInt(this, status, -processed)
      val now = prev - processed
      if (isClosed(now)) {
        // we’re finished
      } else if (now > 1) {
        executionContext.execute(this)
      } else {
        val again = unsafe.getAndAddInt(this, status, -1)
        if (again > 1) {
          executionContext.execute(this)
        }
      }
    }
  }

  protected var behavior: Behavior[T] = _

  protected def next(b: Behavior[T], msg: Any): Unit = {
    if (Behavior.isUnhandled(b)) unhandled(msg)
    behavior = Behavior.canonicalize(b, behavior)
    if (!Behavior.isAlive(behavior)) self.sendSystem(Terminate())
  }

  private def unhandled(msg: Any): Unit = ???

  /**
   * Process the messages in the mailbox
   */
  private def processMessage(msg: T): Unit = {
    next(behavior.message(this, msg), msg)
    if (Thread.interrupted())
      throw new InterruptedException("Interrupted while processing actor messages")
  }

  @tailrec
  private def systemDrain(next: LatestFirstSystemMessageList): EarliestFirstSystemMessageList = {
    val currentList = _systemQueue
    if (currentList.head == NoMessage) new EarliestFirstSystemMessageList(null)
    else if (unsafe.compareAndSwapObject(this, systemQueue, currentList, next)) currentList.reverse
    else systemDrain(next)
  }

  /**
   * Will at least try to process all queued system messages: in case of
   * failure simply drop and go on to the next, because there is nothing to
   * restart here (failure is in ActorCell somewhere …). In case the mailbox
   * becomes closed (because of processing a Terminate message), dump all
   * already dequeued message to deadLetters.
   */
  private def processAllSystemMessages(): Boolean = {
    var interruption: Throwable = null
    var messageList = systemDrain(SystemMessageList.LNil)
    var continue = true
    while ((messageList.nonEmpty) && continue) {
      val msg = messageList.head
      messageList = messageList.tail
      msg.unlink()
      // we know here that systemInvoke ensures that only "fatal" exceptions get rethrown
      continue = processSignal(msg)
      if (Thread.interrupted())
        interruption = new InterruptedException("Interrupted while processing system messages")
      // don’t ever execute normal message when system message present!
      if ((messageList.isEmpty) && continue) messageList = systemDrain(SystemMessageList.LNil)
    }
    /*
     * if we closed the mailbox, we must dump the remaining system messages
     * to deadLetters (this is essential for DeathWatch)
     */
    val dlm = system.deadLetters
    while (messageList.nonEmpty) {
      val msg = messageList.head
      messageList = messageList.tail
      msg.unlink()
      try dlm.sendSystem(msg)
      catch {
        case e: InterruptedException ⇒ interruption = e
        case NonFatal(e) ⇒ system.eventStream.publish(
          Error(e, self.path.toString, this.getClass, "error while enqueuing " + msg + " to deadLetters: " + e.getMessage))
      }
    }
    // if we got an interrupted exception while handling system messages, then rethrow it
    if (interruption ne null) {
      Thread.interrupted() // clear interrupted flag before throwing according to java convention
      throw interruption
    }
    continue
  }

  // logging is not the main purpose, and if it fails there’s nothing we can do
  protected final def publish(e: Logging.LogEvent): Unit = try system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  protected final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass
}
