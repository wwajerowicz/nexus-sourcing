package ch.epfl.bluebrain.nexus.sourcing.projections

import java.util.concurrent.TimeUnit.MILLISECONDS

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import cats.MonadError
import cats.effect.Timer
import ch.epfl.bluebrain.nexus.sourcing.akka.SourcingConfig.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressStorage._
import ch.epfl.bluebrain.nexus.sourcing.retry.RetryStrategy.Linear
import ch.epfl.bluebrain.nexus.sourcing.retry.{Retry, RetryStrategy}
import com.github.ghik.silencer.silent
import pureconfig.generic.auto._
import pureconfig.ConfigSource

import scala.concurrent.duration._

/**
  * Configuration to instrument a [[SequentialTagIndexer]] using an index function.
  *
  * @param tag                the tag to use while selecting the events from the store
  * @param pluginId           the persistence query plugin id
  * @param name               the name of this indexer
  * @param actorOf            a function that given an actor Props and a name it instantiates an ActorRef
  * @param mapping            the mapping function from Event to MappedEvt
  * @param index              the indexing function
  * @param mapInitialProgress a function used to map initial [[ProjectionProgress]]
  * @param mapProgress        a function used to map [[ProjectionProgress]] after every batch
  * @param init               an initialization function that is run before the indexer is (re)started
  * @param batch              the number of events to be grouped
  * @param batchTo            the timeout for the grouping on batches.
  *                           Batching will the amount of time ''batchTo'' to have ''batch'' number of events the retry
  *                           strategy.
  * @param storage            the [[ProgressStorage]]
  * @tparam Event     the event type
  * @tparam MappedEvt the mapped event type
  * @tparam Err       the error type
  * @tparam O         the type of [[ProgressStorage]]
  */
final case class ProjectionConfig[F[_], Event, MappedEvt, Err, O <: ProgressStorage] private (
    tag: String,
    pluginId: String,
    name: String,
    actorOf: Option[(Props, String) => ActorRef],
    mapping: Event => F[Option[MappedEvt]],
    index: List[MappedEvt] => F[Unit],
    mapInitialProgress: ProjectionProgress => F[Unit],
    mapProgress: ProjectionProgress => F[Unit],
    init: F[Unit],
    batch: Int,
    batchTo: FiniteDuration,
    retry: Retry[F, Err],
    storage: O
)

/**
  *
  * Enumeration of offset storage types.
  */
sealed trait ProgressStorage

object ProgressStorage {

  /**
    * The offset is persisted and the failures get logged.
    *
    * @param restart flag to control from where to start consuming messages on boot.
    *                If set to true, it will start consuming messages from the beginning.
    *                If set to false, it will attempt to resume from the previously stored offset (if any)
    */
  final case class Persist(restart: Boolean) extends ProgressStorage

  /**
    * The offset is NOT persisted and the failures do not get logged.
    */
  final case object Volatile extends ProgressStorage

  type Volatile = Volatile.type
}

object ProjectionConfig {

  @SuppressWarnings(Array("LonelySealedTrait"))
  private sealed trait Ready

  @SuppressWarnings(Array("UnusedMethodParameter"))
  private[ProjectionConfig] final case class ProjectionConfigBuilder[F[_]: Timer, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, O <: ProgressStorage](
      tag: Option[String] = None,
      plugin: Option[String] = None,
      name: Option[String] = None,
      actorOf: Option[(Props, String) => ActorRef] = None,
      mapping: Option[Event => F[Option[MappedEvt]]] = None,
      index: Option[List[MappedEvt] => F[Unit]] = None,
      mapInitialProgress: Option[ProjectionProgress => F[Unit]] = None,
      mapProgress: Option[ProjectionProgress => F[Unit]] = None,
      init: F[Unit],
      batch: Int = 1,
      batchTo: FiniteDuration = 50 millis,
      strategy: RetryStrategy = Linear(0 millis, 2000 hours),
      error: MonadError[F, Err],
      storage: O
  ) {

    private implicit val F: MonadError[F, Err] = error

    @silent
    def build(
        implicit e1: Tag =:= Ready,
        e2: Plugin =:= Ready,
        e3: Name =:= Ready,
        e4: Index =:= Ready,
        e5: Mapping =:= Ready
    ): ProjectionConfig[F, Event, MappedEvt, Err, O] =
      (tag, plugin, name, index, mapping) match {
        case (Some(t), Some(p), Some(n), Some(i), Some(m)) =>
          val initProgress = mapInitialProgress.getOrElse { _: ProjectionProgress =>
            F.unit
          }
          val prorgess = mapProgress.getOrElse { _: ProjectionProgress =>
            F.unit
          }
          // format: off
          ProjectionConfig(t, p, n, actorOf, m, i, initProgress,  prorgess, init, batch, batchTo, Retry(strategy), storage)
        // format: on
        case _ => throw new RuntimeException("Unexpected: some of the required fields are not set")
      }

    def tag(value: String): ProjectionConfigBuilder[F, Event, MappedEvt, Ready, Plugin, Name, Index, Mapping, Err, O] =
      copy(tag = Some(value))

    def plugin(value: String): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Ready, Name, Index, Mapping, Err, O] =
      copy(plugin = Some(value))

    def name(value: String): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Ready, Index, Mapping, Err, O] =
      copy(name = Some(value))

    def init(value: F[Unit]): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, O] =
      copy(init = value)

    def actorOf(
        value: (Props, String) => ActorRef
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, O] =
      copy(actorOf = Some(value))

    def index(value: List[MappedEvt] => F[Unit])(
        implicit @silent ev: Mapping =:= Ready
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Ready, Mapping, Err, O] =
      copy(index = Some(value))

    def mapInitialProgress(
        value: ProjectionProgress => F[Unit]
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, O] =
      copy(mapInitialProgress = Some(value))

    def mapProgress(
        value: ProjectionProgress => F[Unit]
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, O] =
      copy(mapProgress = Some(value))

    def mapping[TT, TTO](
        value: TT => F[Option[TTO]]
    ): ProjectionConfigBuilder[F, TT, TTO, Tag, Plugin, Name, Index, Ready, Err, O] =
      copy(mapping = Some(value), index = None)

    def offset[S <: ProgressStorage](
        value: S
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, S] =
      copy(storage = value)

    def batch(value: Int): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, O] =
      copy(batch = value)

    def batch(
        value: Int,
        timeout: FiniteDuration
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, O] =
      copy(batch = value, batchTo = timeout)

    def retry[EE](strategy: RetryStrategy)(
        implicit EE: MonadError[F, EE]
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, EE, O] =
      copy(error = EE, strategy = strategy)

    def restart(value: Boolean)(
        implicit @silent ev: O =:= Persist
    ): ProjectionConfigBuilder[F, Event, MappedEvt, Tag, Plugin, Name, Index, Mapping, Err, Persist] =
      copy(storage = Persist(value))

  }

  /**
    * Retrieves the [[ProjectionConfigBuilder]] with the default pre-filled arguments.
    */
  def builder[F[_]: Timer](
      implicit F: MonadError[F, Throwable]
  ): ProjectionConfigBuilder[F, NotUsed, NotUsed, _, _, _, _, _, Throwable, Persist] =
    ProjectionConfigBuilder(storage = Persist(restart = false), error = F, init = F.unit)

  /**
    * Constructs a new [[ProjectionConfigBuilder]] with some of the arguments pre-filled with the ''as'' configuration
    *
    * @param as the [[ActorSystem]]
    */
  final def fromConfig[F[_]: Timer](
      implicit as: ActorSystem,
      F: MonadError[F, Throwable]
  ): ProjectionConfigBuilder[F, NotUsed, NotUsed, _, _, _, _, _, Throwable, Persist] = {
    val config                           = as.settings.config.getConfig("indexing")
    val timeout                          = FiniteDuration(config.getDuration("batch-timeout", MILLISECONDS), MILLISECONDS)
    val chunk                            = config.getInt("batch")
    val retryConfig: RetryStrategyConfig = ConfigSource.fromConfig(config).at("retry").loadOrThrow[RetryStrategyConfig]

    builder.retry(retryConfig.retryStrategy).batch(chunk, timeout)
  }

}
