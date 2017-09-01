package backbone.aws

import backbone.scaladsl.Backbone.QueueInformation
import com.amazonaws.auth.policy.Policy
import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.model._
import org.slf4j.LoggerFactory
import cats.implicits._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

private[backbone] trait AmazonSqsOps extends AmazonAsync {

  private val logger = LoggerFactory.getLogger(getClass)

  def sqs: AmazonSQSAsync

  def getQueueArn(queueUrl: String)(implicit ec: ExecutionContext): Future[String] = {
    val attributeNames = List(QueueAttributeName.QueueArn.toString)

    for {
      response <- async[GetQueueAttributesRequest, GetQueueAttributesResult](
        sqs.getQueueAttributesAsync(queueUrl, attributeNames.asJava, _)
      )
      attributes = response.getAttributes.asScala
      arn        = attributes(QueueAttributeName.QueueArn.toString)
    } yield arn
  }

  def savePolicy(queueUrl: String, policy: Policy)(implicit ec: ExecutionContext): Future[Unit] = {
    val attributes = Map(QueueAttributeName.Policy.toString -> policy.toJson)
    async[SetQueueAttributesRequest, SetQueueAttributesResult](
      sqs.setQueueAttributesAsync(queueUrl, attributes.asJava, _)
    ).map(_ => ())
  }

  def createQueue(name: String)(implicit ec: ExecutionContext): Future[QueueInformation] = {
    logger.info(s"Creating queue. queueName=$name")
    for {
      createResponse <- async[CreateQueueRequest, CreateQueueResult](sqs.createQueueAsync(name, _))
      url = createResponse.getQueueUrl
      _   <- logger.debug(s"Created queue. queueName=$name, url=$url").pure[Future]
      arn <- getQueueArn(url)
      _   <- logger.debug(s"Requested queueArn. queueName=$name, queueArn=$arn").pure[Future]
    } yield QueueInformation(url, arn)
  }

  def deleteMessage(queueUrl: String, receiptHandle: String)(implicit ec: ExecutionContext): Future[Unit] = {
    async[DeleteMessageRequest, DeleteMessageResult](sqs.deleteMessageAsync(queueUrl, receiptHandle, _)).map(_ => ())
  }

}
