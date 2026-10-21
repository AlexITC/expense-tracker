package net.wiringbits.tasks

import net.wiringbits.config.BackgroundJobsExecutorConfig.TypeSettings
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.jobs.{BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.BackgroundJobsRepository
import net.wiringbits.repositories.daos.backgroundJobParser
import net.wiringbits.repositories.models.BackgroundJobData
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.apache.pekko.stream.{KillSwitches, UniqueKillSwitch}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.Eventually.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{Executors, RepositoryUtils}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Duration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

class BackgroundJobTypeRunnerSpec extends RepositorySpec with RepositoryUtils with BeforeAndAfterAll {

  private implicit lazy val system: ActorSystem = ActorSystem("BackgroundJobTypeRunnerSpec")

  override def afterAll(): Unit = {
    system.terminate().futureValue
    super.afterAll()
  }

  private given Eventually.PatienceConfig = Eventually.PatienceConfig(timeout = 30.seconds, interval = 50.millis)

  private val settings = TypeSettings(parallelism = 1, timeout = 10.seconds, pollInterval = 50.millis)

  private def withEmptyJobQueue[T](runTest: RepositoryComponents => T): T = withRepositories() { repositories =>
    clearBackgroundJobs()(using repositories)
    runTest(repositories)
  }

  "source" should {
    "never run more jobs concurrently than the type parallelism" in withEmptyJobQueue { implicit repositories =>
      val jobs = (1 to 5).map(_ => createBackgroundJobData())
      val gate = Promise[Unit]()
      val inFlight = new AtomicInteger(0)
      val maxInFlight = new AtomicInteger(0)
      val started = new AtomicInteger(0)
      val dispatch: BackgroundJobData => Future[Unit] = _ => {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), math.max)
        started.incrementAndGet()
        gate.future.andThen(_ => inFlight.decrementAndGet())
      }

      val killSwitch = run(BackgroundJobType.SendEmail, settings.copy(parallelism = 2), dispatch)

      eventually { started.get() must be(2) }
      jobs.map(job => jobOf(job.id).status).count(_ == BackgroundJobStatus.Running) must be(2)

      gate.success(())
      eventually {
        jobs.map(job => jobOf(job.id).status).toSet must be(Set(BackgroundJobStatus.Success))
      }
      started.get() must be(5)
      maxInFlight.get() must be(2)
      killSwitch.shutdown()
    }

    "keep processing a type while another type is blocked" in withEmptyJobQueue { implicit repositories =>
      val blockedJob = createBackgroundJobData(backgroundJobType = BackgroundJobType.ExtractBankStatementDraft)
      val jobs = (1 to 3).map(_ => createBackgroundJobData(backgroundJobType = BackgroundJobType.SendEmail))
      val blockedStarted = Promise[Unit]()

      val blockedKillSwitch = run(
        BackgroundJobType.ExtractBankStatementDraft,
        settings,
        _ => {
          blockedStarted.trySuccess(())
          Promise[Unit]().future
        }
      )
      val killSwitch = run(BackgroundJobType.SendEmail, settings, _ => Future.unit)

      blockedStarted.future.futureValue
      eventually {
        jobs.map(job => jobOf(job.id).status).toSet must be(Set(BackgroundJobStatus.Success))
      }
      jobOf(blockedJob.id).status must be(BackgroundJobStatus.Running)
      blockedKillSwitch.shutdown()
      killSwitch.shutdown()
    }

    "record a timed out job as failed and free its slot" in withEmptyJobQueue { implicit repositories =>
      val stuckJob = createBackgroundJobData(executeAt = Instant.now().minus(1, ChronoUnit.MINUTES))
      val nextJob = createBackgroundJobData()
      val before = Instant.now()

      val killSwitch = run(
        BackgroundJobType.SendEmail,
        settings.copy(timeout = 200.millis),
        job => if (job.id == stuckJob.id) Promise[Unit]().future else Future.unit
      )

      eventually {
        jobOf(nextJob.id).status must be(BackgroundJobStatus.Success)
      }
      val stuck = jobOf(stuckJob.id)
      stuck.status must be(BackgroundJobStatus.Failed)
      stuck.errorCount must be(1)
      stuck.executeAt.isAfter(before) must be(true)
      stuck.statusDetails.exists(_.contains("timed out")) must be(true)
      killSwitch.shutdown()
    }

    "record a failed job with exponential backoff" in withEmptyJobQueue { implicit repositories =>
      val job = createBackgroundJobData()
      val before = Instant.now()

      val killSwitch = run(BackgroundJobType.SendEmail, settings, _ => Future.failed(new RuntimeException("boom")))

      eventually {
        jobOf(job.id).status must be(BackgroundJobStatus.Failed)
      }
      val after = Instant.now()
      val failed = jobOf(job.id)
      failed.errorCount must be(1)
      failed.statusDetails must be(Some("boom"))
      // errorCount was 0 when claimed, so the delay is 2^0 minutes
      failed.executeAt.isBefore(before.plus(1, ChronoUnit.MINUTES)) must be(false)
      failed.executeAt.isAfter(after.plus(1, ChronoUnit.MINUTES)) must be(false)
      killSwitch.shutdown()
    }

    "record a successful job" in withEmptyJobQueue { implicit repositories =>
      val job = createBackgroundJobData()

      val killSwitch = run(BackgroundJobType.SendEmail, settings, _ => Future.unit)

      eventually {
        jobOf(job.id).status must be(BackgroundJobStatus.Success)
      }
      killSwitch.shutdown()
    }

    "record a job whose dispatch throws synchronously as failed" in withEmptyJobQueue { implicit repositories =>
      val failingJob = createBackgroundJobData(executeAt = Instant.now().minus(1, ChronoUnit.MINUTES))
      val nextJob = createBackgroundJobData()

      val killSwitch = run(
        BackgroundJobType.SendEmail,
        settings,
        job => if (job.id == failingJob.id) throw new RuntimeException("sync boom") else Future.unit
      )

      eventually {
        jobOf(nextJob.id).status must be(BackgroundJobStatus.Success)
      }
      val failed = jobOf(failingJob.id)
      failed.status must be(BackgroundJobStatus.Failed)
      failed.errorCount must be(1)
      failed.statusDetails must be(Some("sync boom"))
      killSwitch.shutdown()
    }

    "keep processing after a claim fails" in withEmptyJobQueue { implicit repositories =>
      val job = createBackgroundJobData()
      val claimAttempts = new AtomicInteger(0)
      val repository =
        new BackgroundJobsRepository(repositories.database)(using Executors.databaseEC, Clock.systemUTC) {
          override def claimJobs(
              jobType: BackgroundJobType,
              timeout: FiniteDuration,
              limit: Int
          ): Future[List[BackgroundJobData]] =
            if (claimAttempts.incrementAndGet() == 1) Future.failed(new RuntimeException("claim boom"))
            else super.claimJobs(jobType, timeout, limit)
        }

      val killSwitch = runWith(repository, BackgroundJobType.SendEmail, settings, _ => Future.unit)

      eventually {
        jobOf(job.id).status must be(BackgroundJobStatus.Success)
      }
      claimAttempts.get() must be > 1
      killSwitch.shutdown()
    }

    "keep processing after recording an outcome fails" in withEmptyJobQueue { implicit repositories =>
      val unrecordedJob = createBackgroundJobData(executeAt = Instant.now().minus(1, ChronoUnit.MINUTES))
      val nextJob = createBackgroundJobData()
      val repository =
        new BackgroundJobsRepository(repositories.database)(using Executors.databaseEC, Clock.systemUTC) {
          override def setStatusToFailed(backgroundJobId: UUID, executeAt: Instant, failReason: String): Future[Unit] =
            Future.failed(new RuntimeException("record boom"))
        }

      val killSwitch = runWith(
        repository,
        BackgroundJobType.SendEmail,
        settings,
        job => if (job.id == unrecordedJob.id) Future.failed(new RuntimeException("boom")) else Future.unit
      )

      eventually {
        jobOf(nextJob.id).status must be(BackgroundJobStatus.Success)
      }
      // the failure was not recorded, so the job stays running until its claim deadline lapses
      jobOf(unrecordedJob.id).status must be(BackgroundJobStatus.Running)
      killSwitch.shutdown()
    }

    "stop claiming a job once it reaches the allowed errors" in withEmptyJobQueue { implicit repositories =>
      val job = createBackgroundJobData()
      // the repository runs 2 days ahead of the runner, so the backoff (at most 2^9 minutes) never defers the reclaim
      val futureClock = Clock.offset(Clock.systemUTC, Duration.ofDays(2))
      val futureRepository =
        new BackgroundJobsRepository(repositories.database)(using Executors.databaseEC, futureClock)
      val attempts = new AtomicInteger(0)
      val runner = new BackgroundJobTypeRunner(futureRepository)(using executionContext, system, Clock.systemUTC)

      val killSwitch = runner
        .source(
          BackgroundJobType.SendEmail,
          settings,
          _ => {
            attempts.incrementAndGet()
            Future.failed(new RuntimeException("boom"))
          }
        )
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.left)
        .run()

      eventually {
        jobOf(job.id).errorCount must be(10)
      }
      attempts.get() must be(10)
      futureRepository.claimJobs(BackgroundJobType.SendEmail, 1.minute, limit = 10).futureValue must be(Nil)
      attempts.get() must be(10)
      killSwitch.shutdown()
    }
  }

  private def run(
      jobType: BackgroundJobType,
      settings: TypeSettings,
      dispatch: BackgroundJobData => Future[Unit]
  )(using repositories: RepositoryComponents): UniqueKillSwitch =
    runWith(repositories.backgroundJobs, jobType, settings, dispatch)

  private def runWith(
      repository: BackgroundJobsRepository,
      jobType: BackgroundJobType,
      settings: TypeSettings,
      dispatch: BackgroundJobData => Future[Unit]
  ): UniqueKillSwitch = {
    val runner = new BackgroundJobTypeRunner(repository)(using executionContext, system, Clock.systemUTC)
    runner
      .source(jobType, settings, dispatch)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.ignore)(Keep.left)
      .run()
  }

  private def jobOf(id: UUID)(using repositories: RepositoryComponents): BackgroundJobData =
    repositories.database.withConnection { implicit conn =>
      anorm
        .SQL("SELECT * FROM background_jobs WHERE background_job_id = {id}::UUID")
        .on("id" -> id.toString)
        .as(backgroundJobParser.single)
    }
}
