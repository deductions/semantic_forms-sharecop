package deductions.runtime.services

import java.net.URLDecoder
import java.net.URLEncoder
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scala.concurrent.Future
import scala.util.Failure
import org.w3.banana.RDF
import org.w3.banana.RDFOps
import org.w3.banana.RDFStore
import org.w3.banana.io.RDFWriter
import org.w3.banana.SparqlEngine
import org.w3.banana.SparqlOps
import org.w3.banana.io.Turtle
import org.w3.banana._
import org.apache.log4j.Logger
import deductions.runtime.dataset.RDFStoreLocalProvider
import deductions.runtime.utils.Timer
import deductions.runtime.semlogs.LogAPI

trait FormSaver[Rdf <: RDF, DATASET]
    extends RDFStoreLocalProvider[Rdf, DATASET]
    with TypeAddition[Rdf, DATASET]
    with HttpParamsManager[Rdf]
    with LogAPI[Rdf]
    with Timer {

  import ops._
  import sparqlOps._
  import rdfStore.transactorSyntax._
  import rdfStore.graphStoreSyntax._

  val logger:Logger //  = Logger.getRootLogger()

  /**
   * @param map a raw map of HTTP response parameters
   * transactional
   */
  def saveTriples(httpParamsMap: Map[String, Seq[String]]) = {
    logger.debug(s"FormSaver.saveTriples httpParamsMap $httpParamsMap")
    val uriArgs = httpParamsMap.getOrElse("uri", Seq())
    val subjectUriOption = uriArgs.find { uri => uri != "" }
    val graphURIOption = httpParamsMap.getOrElse("graphURI", Seq()).headOption
    println(s"FormSaver.saveTriples uri $subjectUriOption, graphURI $graphURIOption")

    val triplesToAdd = ArrayBuffer[Rdf#Triple]()
    val triplesToRemove = ArrayBuffer[Rdf#Triple]()

    subjectUriOption match {
      case Some(uri0) =>
        val subjectUri = URLDecoder.decode(uri0, "utf-8") // TODO uri0
        val graphURI =
          if (graphURIOption == Some("")) subjectUri
          else URLDecoder.decode(graphURIOption.getOrElse(uri0), "utf-8") // TODO no decode
        httpParamsMap.map {
          case (param0, objects) =>
            val param = URLDecoder.decode(param0, "utf-8") // TODO no decode
            logger.debug(s"saveTriples: httpParam decoded: $param")
            if (param != "url" &&
              param != "uri" &&
              param != "graphURI") {
              val try_ = Try {
                val triple = httpParam2Triple(param)
                logger.debug(s"saveTriples: triple from httpParam: $triple")
                computeDatabaseChanges(triple, objects)
              }
              try_ match {
                case f: Failure[_] => logger.error("saveTriples: " + f)
                case _ =>
              }
            }
        }
        doSave(graphURI)
      case _ =>
    }

    def computeDatabaseChanges(originalTriple: Rdf#Triple, objectsFromUser: Seq[String]) {
      val foaf = FOAFPrefix[Rdf]
      if (originalTriple.predicate == foaf.firstName)
        println(foaf.firstName)
      objectsFromUser.map { objectStringFromUser =>
        // NOTE: a single element in objects
        val objectFromUser = foldNode(originalTriple.objectt)(
          // TODO other forbidden character in URI
          _ => URI(objectStringFromUser.replaceAll(" ", "_")),
          _ => BNode(objectStringFromUser.replaceAll(" ", "_")), // ?? really do this ?
          _ => Literal(objectStringFromUser))
        if (originalTriple.objectt != objectStringFromUser) {
          if (objectStringFromUser != "")
            triplesToAdd +=
              makeTriple(originalTriple.subject, originalTriple.predicate,
                objectFromUser)
          if (originalTriple.objectt.toString() != "")
            triplesToRemove += originalTriple
          logger.debug("computeDatabaseChanges: predicate " + originalTriple.predicate + ", originalTriple.objectt: \"" +
            originalTriple.objectt.toString() + "\"" +
            ", objectStringFromUser \"" + objectStringFromUser + "\"")
        }
      }
    }

    /** transactional */
    def doSave(graphURI: String)
    (implicit userURI: String= "" ) {
      val transaction = dataset.rw({
        time("removeTriples",
          dataset.removeTriples(
            URI(graphURI),
            triplesToRemove.toIterable))
        val res =
          time("appendToGraph",
            dataset.appendToGraph(
              URI(graphURI),
              makeGraph(triplesToAdd)))

        /* TODO
         * add a hook here: return the future to print later that it has been done */
              
        callSaveListeners(triplesToAdd, triplesToRemove)
        
        Future {
          dataset.rw({
            addTypes(triplesToAdd.toSeq,
              Some(URI(graphURI)))
          })
        }
        res
      }) // .flatMap { identity }

      val f = transaction.asFuture

      f onSuccess {
        case _ =>
          logger.info(s""" Successfully stored ${triplesToAdd.size} triples
            ${triplesToAdd.mkString(", ")}
            and removed ${triplesToRemove.size}
            ${triplesToRemove.mkString(", ")}
          in graph $graphURI""")
      }
      f.onFailure { case t => println(s"doSave: Failure $t") }
    }
  }

  ///////////////
  
  val saveListeners = ArrayBuffer[SaveListener]()
  
  def addSaveListener( l: SaveListener) = {
    saveListeners += l
  }
  
  def callSaveListeners(addedTriples: Seq[Rdf#Triple], removedTriples: Seq[Rdf#Triple])
  (implicit userURI: String) 
  = {
    saveListeners . map {
      _ . notifyDataEvent(addedTriples, removedTriples)
    }
  }

}

