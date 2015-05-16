package konstructs

import akka.actor.{ Actor, Stash, ActorRef, Props }

class ShardActor(shard: ShardPosition, chunkStore: ActorRef, chunkGenerator: ActorRef)
    extends Actor with Stash with utils.Scheduled{
  import ShardActor._
  import StorageActor._
  import GeneratorActor._
  import DbActor._
  import PlayerActor.ReceiveBlock
  import Db._
  private val blocks = new Array[Byte](ChunkSize * ChunkSize * ChunkSize)
  private val compressionBuffer = new Array[Byte](ChunkSize * ChunkSize * ChunkSize)
  private val chunks = new Array[Option[Array[Byte]]](ShardSize * ShardSize * ShardSize)
  private var dirty: Set[ChunkPosition] = Set()

  schedule(5000, StoreChunks)

  def loadChunk(chunk: ChunkPosition): Option[Array[Byte]] = {
    val i = chunk.index
    val blocks = chunks(i)
    if(blocks != null) {
      if(!blocks.isDefined)
        stash()
      blocks
    } else {
      chunkStore ! Load(chunk)
      chunks(i) = None
      stash()
      None
    }
  }

  def updateChunk(pos: Position)(update: Byte => Byte) {
    val chunk = pos.chunk
    loadChunk(chunk).map { compressedBlocks =>
      dirty = dirty + chunk
      val local = pos.local
      val size = compress.inflate(compressedBlocks, blocks)
      assert(size == blocks.size)
      val i = local.index
      val oldBlock = blocks(i)
      val block = update(oldBlock)
      blocks(i) = block
      val compressed = compress.deflate(blocks, compressionBuffer)
      chunks(chunk.index) = Some(compressed)
    }
  }

  def receive() = {
    case SendBlocks(to, chunk, _) =>
      loadChunk(chunk).map { blocks =>
        to ! BlockList(chunk, blocks)
      }
    case PutBlock(by, p, w) =>
      updateChunk(p) { old =>
        if(old == 0) {
          sender ! BlockUpdate(p, old.toInt, w)
          w.toByte
        } else {
          by ! ReceiveBlock(w.toByte)
          old
        }
      }
    case DestroyBlock(by, p) =>
      updateChunk(p) { old =>
        by ! ReceiveBlock(old)
        sender ! BlockUpdate(p, old.toInt, 0)
        0
      }
    case Loaded(chunk, blocksOption) =>
      blocksOption match {
        case Some(blocks) =>
          chunks(chunk.index) = Some(blocks)
          unstashAll()
        case None =>
          chunkGenerator ! Generate(chunk)
      }
    case Generated(chunk, blocks) =>
      chunks(chunk.index) = Some(blocks)
      dirty = dirty + chunk
      unstashAll()
    case StoreChunks =>
      dirty.map { chunk =>
        chunks(chunk.index).map { blocks =>
          chunkStore ! Store(chunk, blocks)
        }
      }
      dirty = Set()
  }

}

object ShardActor {
  case object StoreChunks
  case class BlockUpdate(pos: Position, oldW: Int, newW: Int)
  def props(shard: ShardPosition, chunkStore: ActorRef, chunkGenerator: ActorRef) =
    Props(classOf[ShardActor], shard, chunkStore, chunkGenerator)
}
