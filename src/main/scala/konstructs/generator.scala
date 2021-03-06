package konstructs

import akka.actor.{ Actor, ActorRef, Props }

import konstructs.api._

class GeneratorActor(jsonStorage: ActorRef, binaryStorage: ActorRef, factory: BlockFactory) extends Actor {
  import GeneratorActor._

  val worlds = Seq[WorldEntry](
    WorldEntry(
      Box(Position(-1536, 0, -1536), Position(1536, 512, 1536)),
      context.actorOf(FlatWorldActor.props("Terra", Position(3072, 1024, 3072), factory, jsonStorage, binaryStorage))
    )
  )

  def EmptyChunk = new Array[Byte](Db.ChunkSize * Db.ChunkSize * Db.ChunkSize)

  def receive = {
    case Generate(chunk) =>
      worlds.filter(_.box.contains(chunk)).headOption match {
        case Some(entry) =>
          entry.actor forward World.Generate(chunk, entry.box.translate(chunk))
        case None =>
          sender ! Generated(chunk, EmptyChunk)
      }
  }

}

object GeneratorActor {
  case class Generate(position: ChunkPosition)
  case class Generated(position: ChunkPosition, blocks: Array[Byte])

  case class WorldEntry(box: Box, actor: ActorRef)
  def props(jsonStorage: ActorRef, binaryStorage: ActorRef, factory: BlockFactory) =
    Props(classOf[GeneratorActor], jsonStorage, binaryStorage, factory)
}
