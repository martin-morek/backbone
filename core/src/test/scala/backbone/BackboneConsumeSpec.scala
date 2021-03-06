package backbone

import akka.Done
import backbone.consumer.{ConsumerSettings, CountLimitation, ReceiveSettings}
import backbone.json.SnsEnvelope
import backbone.scaladsl.Backbone
import backbone.testutil.Implicits._
import backbone.testutil.{ElasticMQ, MockSNSAsyncClient, TestActorSystem}
import com.amazonaws.services.sqs.model.{CreateQueueRequest, Message, SendMessageRequest}
import io.circe.syntax._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{MustMatchers, WordSpec}

import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import scala.concurrent.Future
import scala.util.Success

import cats.implicits._

class BackboneConsumeSpec
    extends WordSpec
    with ElasticMQ
    with MockSNSAsyncClient
    with MockitoSugar
    with ScalaFutures
    with MustMatchers
    with TestActorSystem {

  override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = Span(3, Seconds))

  val backbone = Backbone()

  "Backbone.consume" should {

    "create a queue with the configured name" in {

      val settings = ConsumerSettings(Nil, "queue-name-1", None, 1, Some(CountLimitation(0)))

      val f: Future[Done] = backbone.consume[String](settings)(s => Consumed)

      whenReady(f) { res =>
        sqsClient.getQueueUrl("queue-name-1").getQueueUrl must be("http://localhost:9324/queue/queue-name-1")
      }

    }

    "create an encrypted queue with the configured name and kms key alias" in {

      val settings = ConsumerSettings(Nil, "queue-name-2", "arn:aws:kms:eu-central-1:123456789012:alias/TestAlias".some, 1, Some(CountLimitation(0)))

      val f: Future[Done] = backbone.consume[String](settings)(s => Consumed)

      whenReady(f) { res =>
        sqsClient.getQueueUrl("queue-name-2").getQueueUrl must be("http://localhost:9324/queue/queue-name-2")
      }

    }

    "fail parsing a wrongly formatted message and keep in on the queue" in {

      val message            = new Message().withBody("blabla")
      val attributes         = HashMap("VisibilityTimeout" -> "0")
      val createQueueRequest = new CreateQueueRequest("no-visibility").withAttributes(attributes.asJava)

      sqsClient.createQueue(createQueueRequest)
      sqsClient.sendMessage(new SendMessageRequest("http://localhost:9324/queue/no-visibility", message.getBody))

      val settings        = ConsumerSettings(Nil, "no-visibility", None, 1, Some(CountLimitation(1)))
      val f: Future[Done] = backbone.consume[String](settings)(s => Consumed)

      whenReady(f) { _ =>
        sqsClient.receiveMessage("http://localhost:9324/queue/no-visibility").getMessages must have size 1
      }

    }

    "consume messages from the queue url" in {
      sendMessage("message", "queue-name")

      val settings        = ConsumerSettings(Nil, "queue-name", None, 1, Some(CountLimitation(1)))
      val f: Future[Done] = backbone.consume[String](settings)(s => Consumed)

      whenReady(f) { _ =>
        sqsClient.receiveMessage("http://localhost:9324/queue/queue-name").getMessages must have size 0
      }
    }

    "consume messages from the queue url if the MessageReader returns no event" in {
      sendMessage("message", "queue-name")

      val settings = ConsumerSettings(Nil, "queue-name", None, 1, Some(CountLimitation(1)))
      val reader   = MessageReader(_ => Success(Option.empty[String]))

      val f: Future[Done] = backbone.consume[String](settings)(s => Rejected)(reader)

      whenReady(f) { _ =>
        sqsClient.receiveMessage("http://localhost:9324/queue/queue-name").getMessages must have size 0
      }
    }

    "reject messages from the queue" in {
      sendMessage("message", "no-visibility")

      val settings        = ConsumerSettings(Nil, "no-visibility", None, 1, Some(CountLimitation(0)), ReceiveSettings(0, 100, 10))
      val f: Future[Done] = backbone.consume[String](settings)(s => Rejected)

      whenReady(f) { _ =>
        sqsClient.receiveMessage("http://localhost:9324/queue/no-visibility").getMessages must have size 1
      }
    }
  }

  private[this] def sendMessage(message: String, queue: String): Unit = {
    val envelope = SnsEnvelope(message)

    val sqsMessage = new Message()
      .withBody(envelope.asJson.toString())
    sqsClient.sendMessage(new SendMessageRequest(s"http://localhost:9324/queue/$queue", sqsMessage.getBody))
  }
}
