import java.io.File

import cats.data.Xor

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.util.Success

// TODO: make a StatsSnapshot object
// TODO: Use that for snapshot reports.
trait TopStatsCollector {
  import Utils._
  val data = TrieMap[String,Long]()

  private val noItems = Seq("No top items to display (map was empty)!")
  def top(count: Int = 5) = {
    if(data.nonEmpty) {
      data.top(count).map {
        case (key, count) => s"$key: $count"
      }
    } else noItems
  }
}

object Stats extends nl.grons.metrics.scala.DefaultInstrumented {
  import Utils._

  val totalMessages = metrics.counter("total-messages")
  val totalWarnings = metrics.counter("total-warnings")
  val totalSingleEvents = metrics.counter("total-single-events")
  val totalOtherEvents = metrics.counter("total-other-events")
  val totalUnknownEvents = metrics.counter("total-unknown-events")
  val totalTweetsEmojis = metrics.counter("total-tweets-with-emojis")

  val tweetsMeter = Metrics.meter

  val start = System.nanoTime()

  def countTypes(tweetType: StreamedTweetType): Unit = {
    tweetType match {
      case WarningEvent(_) => totalWarnings.inc()
      case SingleFieldEvent(_) => totalSingleEvents.inc()
      case Event(_) => totalOtherEvents.inc()
      case UnknownEvent(x) => totalUnknownEvents.inc()
      case TweetEvent(_) => tweetsMeter.mark()
    }
  }

  def percentOfTweets(comparedTo: Long): Double = {
    (comparedTo.toDouble / tweetsMeter.count) * 100.0
  }

  object Photos extends TopStatsCollector {
    val total = metrics.counter("total-tweets-with-a-photo")

    private val photoDomains = Array("pic.twitter", "instagram", "imgur")
    def hasPhotoUrl(tweet: Tweet): Boolean = {
      tweet.entities.media.exists { media =>
        media.exists { photo =>
          domainFrom(photo.expanded_url).map(photoDomains.contains).getOrElse(false)
        }
      }
    }

    def collect(tweet: Tweet): Unit = {
      if(hasPhotoUrl(tweet)) total.inc()
    }
  }

  object Urls extends TopStatsCollector {
    val total = metrics.counter("total-tweets-with-a-url")
    def collect(tweet: Tweet): Unit = {
      if(tweet.entities.urls.nonEmpty) {
        total.inc()
        tweet.entities.urls.foreach {
          case Url(expanded_url) =>
            domainFrom(expanded_url).foreach(data.addOrIncr)
        }
      }
    }
  }

  object Hashtags extends TopStatsCollector {
    def collect(tweet: Tweet): Unit = {
      tweet.entities.hashtags.foreach(tag => data.addOrIncr(tag.text))
    }
  }

  object Languages extends TopStatsCollector {
    def collect(tweet: Tweet): Unit = {
      tweet.lang.foreach(data.addOrIncr)
    }
  }

  object Countries extends TopStatsCollector {
    def collect(tweet: Tweet): Unit = {
      tweet.place.foreach(country => data.addOrIncr(country.country_code))
    }
  }

  object Emojis extends TopStatsCollector {
    val total = metrics.counter("total-tweets-with-emojis")

    // http://stackoverflow.com/questions/30767631/check-if-emoji-character
    private val emojiPattern = "([\\u20a0-\\u32ff\\ud83c\\udc00-\\ud83d\\udeff\\udbb9\\udce5-\\udbb9\\udcee])".r

    def collect(emojiData: Map[String, String])(tweet: Tweet): Unit = {
      val results = emojiPattern.findAllIn(tweet.text).flatMap(emojiData.get).toVector
      results.foreach(data.addOrIncr)
      if (results.nonEmpty) total.inc()
    }

    // convert hex string to utf-16 string.
    def hexStringToUnicode(hexStrings: String): Option[String] = {
      try {
        val ints = hexStrings.split('-').map(x => Integer.parseInt(x, 16))
        Some(new String(ints, 0, ints.length))
      } catch {
        case e: Throwable =>
          println(s"Failed to convert to $hexStrings to string. reason: ${e.getCause}")
          None
      }
    }

    def readData(name: String): Map[String, String] = {
      val file = new File(name)
      if (!file.exists()) {
        println(s"Couldn't open $name! Tried the follow path: ${file.getAbsolutePath}")
        Map.empty[String, String]
      } else {
        import io.circe.generic.semiauto._

        implicit val emojiDecoder = deriveDecoder[EmojiEntry]

        io.circe.jawn.CirceSupportParser.parseFromFile(file).map { x =>
          x.as[List[EmojiEntry]]
        } match {
          case Success(Xor.Right(emojisDecoded)) =>
            println(s"collected emoji data: ${emojisDecoded.size}")
            emojisDecoded.foldLeft(Map.newBuilder[String, String]) {
              case (acc, emoji) =>
                hexStringToUnicode(emoji.unified).foreach(emojiUnified => acc += emojiUnified -> emoji.short_name)
                Seq[Option[String]](emoji.au, emoji.google, emoji.docomo, emoji.softbank)
                  .flatten
                  .flatMap(hexStringToUnicode)
                  .foreach(emojiSymbol => acc += emojiSymbol -> emoji.short_name)

                acc
            }.result()
          case fail =>
            println(s"Failed to parse emojis: $fail")
            Map.empty[String, String]
        }
      }

    }

    case class EmojiEntry(unified: String, docomo: Option[String],
                          au: Option[String], softbank: Option[String],
                          google: Option[String], short_name: String)
  }

  // TODO: This would generate stat snapshots that could be displayed,
  // transformed to json, or stored in a DB.
  object Reporter {
    def displayFinalStats(): Unit = {
      val end = System.nanoTime()
      println("=" * 20)
      println("Final stats:")
      println(s"Runtime: ${Duration.fromNanos(end - start).toMinutes} minutes")
      println(s"Total messages processed ${totalMessages.count}")
      println(s"Total warnings ${totalWarnings.count}")
      println(s"Total other events ${totalOtherEvents.count}")
      println(s"Total single field events ${totalSingleEvents.count}")
      println(s"Total unknown events ${totalUnknownEvents.count}")
    }

    def displayStats(sep: Boolean): Unit = {
      if(sep) println("=" * 20)

      println(s"Total standard tweets events ${tweetsMeter.count}")
      println(s"Tweets per second: ${tweetsMeter.meanRate}")
      println(s"Average tweets per second in the last minute: ${tweetsMeter.oneMinuteRate()} (est. ${tweetsMeter.oneMinuteRate() * 60.0}/min)")
      println(s"Average tweets per second in the last hour: ${tweetsMeter.sixtyMinuteRate()} (est. ${tweetsMeter.sixtyMinuteRate() * 3600.0}/hour)")

      println(s"Percent of tweets with emojis (${totalTweetsEmojis.count} total): ${percentOfTweets(totalTweetsEmojis.count)}")
      println(s"Percent of tweets with urls (${Stats.Urls.total.count} total): ${percentOfTweets(Stats.Urls.total.count)}")
      println(s"Percent of tweets with photos (${Stats.Photos.total.count} total): ${percentOfTweets(Stats.Photos.total.count)}")

      println(s"Top hashtags: ${Stats.Hashtags.top().mkString(", ")}")
      println(s"Top languages: ${Stats.Languages.top().mkString(", ")}")
      println(s"Top countries: ${Stats.Countries.top().mkString(", ")}")
      println(s"Top emojis: ${Stats.Emojis.top().mkString(", ")}")
      println(s"Top urls: ${Stats.Urls.top().mkString(", ")}")
      println()
      println()
    }
  }
}

