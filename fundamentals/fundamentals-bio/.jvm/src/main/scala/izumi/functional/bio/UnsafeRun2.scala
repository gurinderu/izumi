package izumi.functional.bio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, ThreadPoolExecutor}
import izumi.functional.bio.Exit.ZIOExit
import izumi.functional.bio.UnsafeRun2.InterruptAction
import zio.internal.tracing.TracingConfig
import zio.internal.{Executor, Platform, Tracing}
import zio.{Cause, IO, Runtime, Supervisor, ZIO}

import scala.annotation.nowarn
import scala.concurrent.Future

trait UnsafeRun2[F[_, _]] {
  def unsafeRun[E, A](io: => F[E, A]): A
  def unsafeRunSync[E, A](io: => F[E, A]): Exit[E, A]

  def unsafeRunAsync[E, A](io: => F[E, A])(callback: Exit[E, A] => Unit): Unit
  def unsafeRunAsyncAsFuture[E, A](io: => F[E, A]): Future[Exit[E, A]]

  def unsafeRunAsyncInterruptible[E, A](io: => F[E, A])(callback: Exit[E, A] => Unit): InterruptAction[F]
  def unsafeRunAsyncAsInterruptibleFuture[E, A](io: => F[E, A]): (Future[Exit[E, A]], InterruptAction[F])
}

object UnsafeRun2 {
  def apply[F[_, _]: UnsafeRun2]: UnsafeRun2[F] = implicitly

  def createZIO(platform: Platform): ZIORunner = new ZIORunner(platform)

  def createZIO(
    cpuPool: ThreadPoolExecutor,
    handler: FailureHandler = FailureHandler.Default,
    yieldEveryNFlatMaps: Int = 1024,
    tracingConfig: TracingConfig = TracingConfig.enabled,
  ): ZIORunner = {
    new ZIORunner(new ZIOPlatform(cpuPool, handler, yieldEveryNFlatMaps, tracingConfig))
  }

  def newZioTimerPool(): ScheduledExecutorService = {
    Executors.newScheduledThreadPool(1, new NamedThreadFactory("zio-timer", true))
  }

//  def createMonixBIO(s: Scheduler, opts: monix.bio.IO.Options): UnsafeRun2[monix.bio.IO] = new MonixBIORunner(s, opts)

  /**
    * @param interrupt May semantically block until the target computation either finishes completely or finishes running
    *                  its finalizers, depending on the underlying effect type.
    */
  final case class InterruptAction[F[_, _]](interrupt: F[Nothing, Unit]) extends AnyVal

//  class MonixBIORunner(val s: Scheduler, val opts: monix.bio.IO.Options) extends UnsafeRun2[monix.bio.IO] {
//    override def unsafeRun[E, A](io: => bio.IO[E, A]): A = {
//      io.leftMap(TypedError(_)).runSyncUnsafeOpt()(s, opts, implicitly, implicitly)
//    }
//    override def unsafeRunSync[E, A](io: => bio.IO[E, A]): Exit[E, A] = {
//      io.sandboxExit.runSyncUnsafeOpt()(s, opts, implicitly, implicitly)
//    }
//    override def unsafeRunAsync[E, A](io: => bio.IO[E, A])(callback: Exit[E, A] => Unit): Unit = {
//      io.runAsyncOpt(exit => callback(Exit.MonixExit.toExit(exit)))(s, opts); ()
//    }
//    override def unsafeRunAsyncAsFuture[E, A](io: => bio.IO[E, A]): Future[Exit[E, A]] = {
//      val p = scala.concurrent.Promise[Exit[E, A]]()
//      unsafeRunAsync(io)(p.success)
//      p.future
//    }
//    override def unsafeRunAsyncInterruptible[E, A](io: => bio.IO[E, A])(callback: Exit[E, A] => Unit): InterruptAction[bio.IO] = {
//      val canceler = io.runAsyncOptF {
//        case Left(e) => callback(Exit.MonixExit.toExit(e))
//        case Right(value) => callback(Exit.Success(value))
//      }(s, opts)
//      InterruptAction(canceler)
//    }
//
//    override def unsafeRunAsyncAsInterruptibleFuture[E, A](io: => bio.IO[E, A]): (Future[Exit[E, A]], InterruptAction[bio.IO]) = {
//      val p = scala.concurrent.Promise[Exit[E, A]]()
//      val canceler = unsafeRunAsyncInterruptible(io)(p.success)
//      (p.future, canceler)
//    }
//  }

  sealed trait FailureHandler
  object FailureHandler {
    case object Default extends FailureHandler
    final case class Custom(handler: Exit.Failure[Any] => Unit) extends FailureHandler
  }

  class ZIORunner(
    val platform: Platform
  ) extends UnsafeRun2[IO] {

    val runtime: Runtime[Unit] = Runtime((), platform)

    override def unsafeRun[E, A](io: => IO[E, A]): A = {
      unsafeRunSync(io) match {
        case Exit.Success(value) =>
          value

        case failure: Exit.Failure[?] =>
          throw failure.trace.unsafeAttachTrace(TypedError(_))
      }
    }

    override def unsafeRunAsync[E, A](io: => IO[E, A])(callback: Exit[E, A] => Unit): Unit = {
      val interrupted = new AtomicBoolean(true)
      runtime.unsafeRunAsync[E, A] {
        ZIOExit.ZIOSignalOnNoExternalInterruptFailure(io)(ZIO.effectTotal(interrupted.set(false)))
      }(exitResult => callback(ZIOExit.toExit(exitResult)(interrupted.get())))
    }

    override def unsafeRunSync[E, A](io: => IO[E, A]): Exit[E, A] = {
      val interrupted = new AtomicBoolean(true)
      val result = runtime.unsafeRunSync {
        ZIOExit.ZIOSignalOnNoExternalInterruptFailure(io)(ZIO.effectTotal(interrupted.set(false)))
      }
      ZIOExit.toExit(result)(interrupted.get())
    }

    override def unsafeRunAsyncAsFuture[E, A](io: => IO[E, A]): Future[Exit[E, A]] = {
      val p = scala.concurrent.Promise[Exit[E, A]]()
      unsafeRunAsync(io)(p.success)
      p.future
    }

    override def unsafeRunAsyncInterruptible[E, A](io: => IO[E, A])(callback: Exit[E, A] => Unit): InterruptAction[IO] = {
      val interrupted = new AtomicBoolean(true)

      val canceler = runtime.unsafeRunAsyncCancelable {
        ZIOExit.ZIOSignalOnNoExternalInterruptFailure(io)(ZIO.effectTotal(interrupted.set(false)))
      }(exit => callback(ZIOExit.toExit(exit)(interrupted.get())))

      val cancelerEffect = ZIO.effectTotal { val _: zio.Exit[E, A] = canceler(zio.Fiber.Id.None) }
      InterruptAction(cancelerEffect)
    }

    override def unsafeRunAsyncAsInterruptibleFuture[E, A](io: => IO[E, A]): (Future[Exit[E, A]], InterruptAction[IO]) = {
      val p = scala.concurrent.Promise[Exit[E, A]]()
      val canceler = unsafeRunAsyncInterruptible(io)(p.success)
      (p.future, canceler)
    }
  }

  class ZIOPlatform(
    cpuPool: ThreadPoolExecutor,
    handler: FailureHandler,
    yieldEveryNFlatMaps: Int,
    tracingConfig: TracingConfig,
  ) extends Platform {

    override val executor: Executor = Executor.fromThreadPoolExecutor(_ => yieldEveryNFlatMaps)(cpuPool)
    override val tracing: Tracing = Tracing.enabledWith(tracingConfig)
    override val supervisor: Supervisor[Any] = Supervisor.none
    override val yieldOnStart: Boolean = true

    override def reportFailure(cause: Cause[Any]): Unit = {
      handler match {
        case FailureHandler.Default =>
          // do not log interruptions
          if (!cause.interrupted) {
            System.err.println(cause.prettyPrint)
          }

        case FailureHandler.Custom(f) =>
          f(ZIOExit.toExit(cause)(outerInterruptionConfirmed = true))
      }
    }

    override def fatal(t: Throwable): Boolean = {
      t.isInstanceOf[VirtualMachineError]
    }

    override def reportFatal(t: Throwable): Nothing = {
      t.printStackTrace()
      sys.exit(-1)
    }

  }

  final class NamedThreadFactory(name: String, daemon: Boolean) extends ThreadFactory {

    @nowarn("msg=deprecated")
    private val parentGroup =
      Option(System.getSecurityManager).fold(Thread.currentThread().getThreadGroup)(_.getThreadGroup)

    private val threadGroup = new ThreadGroup(parentGroup, name)
    private val threadCount = new AtomicInteger(1)
    private val threadHash = Integer.toUnsignedString(this.hashCode())

    override def newThread(r: Runnable): Thread = {
      val newThreadNumber = threadCount.getAndIncrement()

      val thread = new Thread(threadGroup, r)
      thread.setName(s"$name-$newThreadNumber-$threadHash")
      thread.setDaemon(daemon)

      thread
    }

  }
}
