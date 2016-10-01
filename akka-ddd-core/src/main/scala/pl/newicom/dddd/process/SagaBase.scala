package pl.newicom.dddd.process

import akka.actor.ActorPath
import akka.contrib.pattern.ReceivePipeline
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import org.joda.time.DateTime
import pl.newicom.dddd.actor.GracefulPassivation
import pl.newicom.dddd.aggregate._
import pl.newicom.dddd.delivery.protocol.DeliveryHandler
import pl.newicom.dddd.messaging.MetaData._
import pl.newicom.dddd.messaging.command.CommandMessage
import pl.newicom.dddd.messaging.event.EventMessage
import pl.newicom.dddd.messaging.{Deduplication, Message}
import pl.newicom.dddd.office.{Office, OfficeId}
import pl.newicom.dddd.persistence.PersistentActorLogging
import pl.newicom.dddd.scheduling.ScheduleEvent

trait SagaBase extends BusinessEntity with GracefulPassivation with PersistentActor
  with AtLeastOnceDelivery with ReceivePipeline with Deduplication with PersistentActorLogging {

  private var _lastEventMessage: Option[EventMessage] = None

  def sagaId = self.path.name

  def officeId: OfficeId

  override def persistenceId: String = officeId.clerkGlobalId(id)

  override def id = sagaId
  override def department = officeId.department

  def currentEventMsg: EventMessage = _lastEventMessage.get

  def schedulingOffice: Option[Office] = None

  def officePath: ActorPath = context.parent.path.parent

  def deliverMsg(office: ActorPath, msg: Message): Unit = {
    deliver(office)(deliveryId => {
      msg.withMetaAttribute(DeliveryId, deliveryId)
    })
  }


  def deliverCommand(office: ActorPath, command: Command): Unit = {
    deliverMsg(office, CommandMessage(command).causedBy(currentEventMsg))
  }

  def schedule(event: DomainEvent, deadline: DateTime, correlationId: EntityId = sagaId): Unit = {
    schedulingOffice.fold(throw new UnsupportedOperationException("Scheduling Office is not defined.")) { schOffice =>
      val command = ScheduleEvent("global", officePath, deadline, event)
      schOffice deliver CommandMessage(command).withCorrelationId(correlationId)
    }
  }

  protected def acknowledgeEvent(em: Message) {
    val deliveryReceipt = em.deliveryReceipt()
    sender() ! deliveryReceipt
    log.debug(s"Delivery receipt (for received event) sent ($deliveryReceipt)")
  }

  override def messageProcessed(msg: Message): Unit = {
    _lastEventMessage = msg match {
      case em: EventMessage =>
        Some(em)
      case _ => None
    }
    super.messageProcessed(msg)
  }

  override def handleDuplicated(msg: Message) =
    acknowledgeEvent(msg)


  // DSL Helper
  implicit def deliveryHandler: DeliveryHandler = {
    (ap: ActorPath, msg: Any) => msg match {
      case c: Command => deliverCommand(ap, c)
      case m: Message => deliverMsg(ap, m)
    }
  }.tupled

}
