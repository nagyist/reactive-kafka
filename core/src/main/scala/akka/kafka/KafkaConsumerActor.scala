/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.kafka

import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Status, Terminated}
import akka.event.LoggingReceive
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import scala.collection.JavaConverters._
import java.util.concurrent.locks.LockSupport

object KafkaConsumerActor {
  case class StoppingException() extends RuntimeException("Kafka consumer is stopping")
  def props[K, V](settings: ConsumerSettings[K, V]): Props = {
    Props(new KafkaConsumerActor(settings)).withDispatcher(settings.dispatcher)
  }

  private[kafka] object Internal {
    //requests
    final case class Assign(tps: Set[TopicPartition])
    final case class AssignWithOffset(tps: Map[TopicPartition, Long])
    final case class Subscribe(topics: Set[String], listener: ConsumerRebalanceListener)
    final case class SubscribePattern(pattern: String, listener: ConsumerRebalanceListener)
    final case class RequestMessages(topics: Set[TopicPartition])
    case object Stop
    final case class Commit(offsets: Map[TopicPartition, Long])
    //responses
    final case class Assigned(partition: List[TopicPartition])
    final case class Revoked(partition: List[TopicPartition])
    final case class Messages[K, V](messages: Iterator[ConsumerRecord[K, V]])
    final case class Committed(offsets: Map[TopicPartition, OffsetAndMetadata])
    //internal
    private[KafkaConsumerActor] case object Poll
    private val number = new AtomicInteger()
    def nextNumber() = {
      number.incrementAndGet()
    }
  }

  private[kafka] def rebalanceListener(onAssign: Iterable[TopicPartition] => Unit, onRevoke: Iterable[TopicPartition] => Unit) = new ConsumerRebalanceListener {
    override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]): Unit = {
      onAssign(partitions.asScala)
    }
    override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]): Unit = {
      onRevoke(partitions.asScala)
    }
  }

  private class WrappedAutoPausedListener(client: KafkaConsumer[_, _], listener: ConsumerRebalanceListener) extends ConsumerRebalanceListener {
    override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]): Unit = {
      client.pause(partitions)
      listener.onPartitionsAssigned(partitions)
    }

    override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]): Unit = {
      listener.onPartitionsRevoked(partitions)
    }
  }
}

private[kafka] class KafkaConsumerActor[K, V](settings: ConsumerSettings[K, V])
    extends Actor with ActorLogging {
  import KafkaConsumerActor.Internal._
  import KafkaConsumerActor._

  def pollTimeout() = settings.pollTimeout
  def pollInterval() = settings.pollInterval

  val pollTask: Cancellable =
    context.system.scheduler.schedule(pollInterval(), pollInterval(), self, Poll)(context.dispatcher)

  var requests = Map.empty[TopicPartition, ActorRef]
  var consumer: KafkaConsumer[K, V] = _
  var commitsInProgress = 0
  var stopInProgress = false

  def receive: Receive = LoggingReceive {
    case Assign(tps) =>
      val previousAssigned = consumer.assignment()
      consumer.assign((tps.toSeq ++ previousAssigned.asScala).asJava)
    case AssignWithOffset(tps) =>
      val previousAssigned = consumer.assignment()
      consumer.assign((tps.keys.toSeq ++ previousAssigned.asScala).asJava)
      tps.foreach {
        case (tp, offset) => consumer.seek(tp, offset)
      }
    case Commit(offsets) =>
      val commitMap = offsets.mapValues(new OffsetAndMetadata(_))
      val reply = sender()
      commitsInProgress += 1
      consumer.commitAsync(commitMap.asJava, new OffsetCommitCallback {
        override def onComplete(offsets: util.Map[TopicPartition, OffsetAndMetadata], exception: Exception): Unit = {
          // this is invoked on the thread calling consumer.poll which will always be the actor, so it is safe
          commitsInProgress -= 1
          if (exception != null) reply ! Status.Failure(exception)
          else reply ! Committed(offsets.asScala.toMap)
        }
      })
      //right now we can not store commits in consumer - https://issues.apache.org/jira/browse/KAFKA-3412
      poll()
    case Subscribe(topics, listener) =>
      consumer.subscribe(topics.toList.asJava, new WrappedAutoPausedListener(consumer, listener))
    case SubscribePattern(pattern, listener) =>
      consumer.subscribe(Pattern.compile(pattern), new WrappedAutoPausedListener(consumer, listener))
    case Poll =>
      poll()
    case RequestMessages(topics) =>
      context.watch(sender())
      requests ++= topics.map(_ -> sender()).toMap
      poll()
    case Stop =>
      if (commitsInProgress == 0) {
        context.stop(self)
      }
      else {
        stopInProgress = true
        context.become(stopping)
      }
    case Terminated(ref) =>
      requests = requests.filter(_._2 == ref)
  }

  def stopping: Receive = LoggingReceive {
    case Poll =>
      poll()
    case Stop =>
    case _: Terminated =>
    case msg @ (_: Commit | _: RequestMessages) =>
      sender() ! Status.Failure(StoppingException())
    case msg @ (_: Assign | _: AssignWithOffset | _: Subscribe | _: SubscribePattern) =>
      log.warning("Got unexpected message {} when KafkaConsumerActor is in stopping state", msg)
  }

  override def preStart(): Unit = {
    super.preStart()
    requests = Map.empty[TopicPartition, ActorRef]
    consumer = settings.createKafkaConsumer()
    commitsInProgress = 0
  }

  override def postStop(): Unit = {
    pollTask.cancel()
    consumer.close()
    super.postStop()
  }

  def poll() = {
    //set partitions to fetch
    val partitionsToFetch = requests.keys.toSet
    consumer.assignment().asScala.foreach { tp =>
      if (partitionsToFetch.contains(tp)) consumer.resume(java.util.Collections.singleton(tp))
      else consumer.pause(java.util.Collections.singleton(tp))
    }

    if (requests.isEmpty) {
      // no outstanding requests so we don't expect any messages back, but we should anyway
      // drive the KafkaConsumer by polling

      def checkNoResult(rawResult: ConsumerRecords[K, V]): Unit =
        if (!rawResult.isEmpty)
          throw new IllegalStateException(s"Got ${rawResult.count} unexpected messages")

      checkNoResult(consumer.poll(0))

      // For commits we try to avoid blocking poll because a commit normally succeeds after a few
      // poll(0). Using poll(1) will always block for 1 ms, since there are no messages.
      // Therefore we do 10 poll(0) with short 10 μs delay followed by 1 poll(1).
      // If it's still not completed it will be tried again after the scheduled Poll.
      var i = 10
      while (i > 0 && commitsInProgress > 0) {
        LockSupport.parkNanos(10 * 1000)
        val pollTimeout = if (i == 1) 1L else 0L
        checkNoResult(consumer.poll(pollTimeout))
        i -= 1
      }

    }
    else {

      val rawResult = consumer.poll(pollTimeout().toMillis)
      if (!rawResult.isEmpty) {
        // split tps by reply actor
        val replyByTP = requests
          .groupBy { case (tp, ref) => ref }
          .mapValues(_.keys.toSet)

        //send messages to actors
        replyByTP.foreach {
          case (ref, tps) =>
            //gather all messages for ref
            val messages = tps.foldLeft[Iterator[ConsumerRecord[K, V]]](Iterator.empty) {
              case (acc, tp) =>
                val tpMessages = rawResult.records(tp).asScala.iterator
                if (acc.isEmpty) tpMessages
                else acc ++ tpMessages
            }
            if (messages.nonEmpty) {
              ref ! Messages(messages)
            }
        }
        //check the we got only requested partitions and did not drop any messages
        if ((rawResult.partitions().asScala -- partitionsToFetch).nonEmpty)
          throw new IllegalArgumentException(s"Unexpected records polled. Expected: $partitionsToFetch, " +
            s"result: ${rawResult.partitions()}, consumer assignment: ${consumer.assignment()}")

        //remove tps for which we got messages
        requests --= rawResult.partitions().asScala
      }
    }
    if (stopInProgress && commitsInProgress == 0) {
      context.stop(self)
    }
  }

}
