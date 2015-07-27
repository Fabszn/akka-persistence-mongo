package akka.contrib.persistence.mongodb

import akka.persistence._
import reactivemongo.api.{Cursor, ReadPreference}
import reactivemongo.bson._
import DefaultBSONHandlers._

import scala.collection.immutable.{Seq => ISeq}
import scala.concurrent._
import scala.util.{Failure, Try, Success}

class RxMongoJournaller(driver: RxMongoPersistenceDriver) extends MongoPersistenceJournallingApi {

  import RxMongoSerializers._
  import JournallingFieldNames._

  private[this] implicit val serialization = driver.serialization
  private[this] lazy val writeConcern = driver.journalWriteConcern

  private[this] def journal(implicit ec: ExecutionContext) = driver.journal

  private[this] def journalRangeQuery(pid: String, from: Long, to: Long) =
    BSONDocument(ATOM ->
      BSONDocument("$elemMatch" ->
        BSONDocument(PROCESSOR_ID -> pid,
                      FROM -> BSONDocument("$gte" -> from),
                      FROM -> BSONDocument("$lte" -> to))))

  private[mongodb] def journalRange(pid: String, from: Long, to: Long)(implicit ec: ExecutionContext) =
    journal.find(journalRangeQuery(pid, from, to))
           .projection(BSONDocument(s"$ATOM.$$.$EVENTS" -> 1))
           .cursor[BSONDocument](ReadPreference.primary)
           .foldWhile(ISeq.empty[Event])(unwind(to),{ case(_,thr) => Cursor.Fail(thr)})

  private[this] def unwind(maxSeq: Long)(s: ISeq[Event], doc: BSONDocument) = {
    val arr = doc.as[BSONArray](ATOM).values.flatMap {
      case a:BSONDocument => a.as[BSONArray](EVENTS).values
    }
    val docs = arr.collect {
      case d:BSONDocument => driver.deserializeJournal(d)
    }.takeWhile(_.sn <= maxSeq)
    if (docs.size == arr.length) Cursor.Cont(s ++ docs)
    else Cursor.Done(s ++ docs)
  }

  private[mongodb] override def atomicAppend(aw: AtomicWrite)(implicit ec: ExecutionContext) = {
    Future(Try(driver.serializeJournal(Atom[BSONDocument](aw)))).flatMap {
      case Success(document:BSONDocument) => journal.insert(document, writeConcern).map(_ => Success(()))
      case f:Failure[_] => Future.successful(Failure[Unit](f.exception))
    }
  }

  private[mongodb] override def deleteFrom(persistenceId: String, toSequenceNr: Long)(implicit ec: ExecutionContext) = {
    val query = journalRangeQuery(persistenceId, 0L, toSequenceNr)
    journal.update(query,
      BSONDocument(
        "$pull" -> BSONDocument(s"$ATOM.$$.$EVENTS" -> BSONDocument(
            PROCESSOR_ID -> persistenceId,
            SEQUENCE_NUMBER -> BSONDocument("$lte" -> toSequenceNr)
          )),
        "$set" -> BSONDocument(s"$ATOM.$$.$FROM" -> (toSequenceNr + 1))
      ), writeConcern, upsert = false, multi = true).andThen {
        case Success(wr) if wr.ok =>
          journal.remove(
            BSONDocument("$and" -> BSONArray(query, BSONDocument(s"$ATOM.$EVENTS" -> BSONDocument("$size" -> 0)))),
            writeConcern)
      }.map(_ => ())
  }

  private[mongodb] override def maxSequenceNr(pid: String, from: Long)(implicit ec: ExecutionContext) =
    journal.find(BSONDocument(s"$ATOM.$PROCESSOR_ID" -> pid))
      .projection(BSONDocument(s"$ATOM.$TO" -> 1))
      .sort(BSONDocument(s"$ATOM.$TO" -> -1))
      .cursor[BSONDocument](ReadPreference.primary)
      .headOption
      .map(l => l.map(_.as[BSONArray](ATOM).values.collect {
        case d: BSONDocument => d.getAs[Long](TO)
      }.flatten.foldLeft(0L)(implicitly[Ordering[Long]].max)).getOrElse(0L))

  private[mongodb] override def replayJournal(pid: String, from: Long, to: Long, max: Long)(replayCallback: PersistentRepr ⇒ Unit)(implicit ec: ExecutionContext) =
    if (max == 0L) Future.successful(())
    else {
      val maxInt = max.toIntWithoutWrapping
      journalRange(pid, from, to).map(events => events.take(maxInt).map(_.toRepr).foreach(replayCallback))
    }

}