package io.github.legosteen11.rssfeedbot

import com.google.common.base.Stopwatch
import com.sun.syndication.feed.synd.SyndFeed
import com.sun.syndication.io.FeedException
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.apache.http.client.ClientProtocolException
import java.io.IOException
import java.util.concurrent.TimeUnit

object RSSQueue {
    private val TIME_IN_BETWEEN = 500L // 500 seconds between fetching new posts
    private val TIME_BETWEEN_REFRESHING_FEED = 30000L // 5 minutes before fetching the same feed again

    private val queue = arrayListOf<QueueObject>()
    private var running = false

    init {
        Thread {
            runBlocking {
                while (true) {
                    if (running)
                        executeOne()

                    delay(TIME_IN_BETWEEN)
                }
            }
        }.start()
    }

    suspend private fun executeOne() {
        val queueObject = queue.firstOrNull() ?: return

        queue.remove(queueObject)

        queueObject.execute()
    }

    fun start() {
        running = true
    }

    fun stop() {
        running = false
    }

    fun addToQueue(url: String, hasPriority: Boolean = false, callback: (suspend (FetchStatus, SyndFeed?) -> Unit)) {
        // add feed to queue
        val queueObject = QueueObject(url, callback)

        if(hasPriority) {
            queue.add(0, queueObject)
        } else {
            queue.add(queueObject)
        }
    }

    fun addToQueue(feed: Feed, hasPriority: Boolean = false, callback: suspend (FetchStatus, SyndFeed?) -> Unit) {
        // add feed to queue
        addToQueue(Feed.getUrl(feed.resource, feed.type), hasPriority, callback)
    }

    fun addToInfiniteQueue(feed: Feed, callback: suspend (FetchStatus, SyndFeed?) -> Unit) {
        // add feed to queue
        addToQueue(feed) { status, syndFeed ->
            callback(status, syndFeed)

            delay(TIME_BETWEEN_REFRESHING_FEED)

            // add the feed back to the queue
            addToInfiniteQueue(feed, callback)
        }
    }

    fun addFeedToQueue(feed: Feed) {
        addToInfiniteQueue(feed) { status, syndFeed ->
            if(status != RSSQueue.FetchStatus.ERROR && syndFeed != null) {
                feed.notifySubscribersOfNewPosts(syndFeed)
            }
        }
    }

    private class QueueObject(val url: String, val callback: suspend (FetchStatus, SyndFeed?) -> Unit) {
        fun upInQueue() {
            queue.add(1, this)
        }

        suspend fun execute() {
            if (!canFetch(url)) {
                logger.debug("$url has to wait, so upping it in the queue by one.")
                upInQueue()
                return
            }

            logger.debug("starting $url")
            fetched(url)

            var syndFeed: SyndFeed? = null
            var status = FetchStatus.ERROR

            try {
                syndFeed = Feed.getFeed(url)

                status = FetchStatus.SUCCESS
            } catch (e: IOException) {
                logger.error("IO Exception while trying to fetch $url")
            } catch (e: IllegalArgumentException) {
                logger.error("Feed type or url was not understood for $url")
            } catch (e: FeedException) {
                logger.error("The feed $url could not be parsed")
            } catch (e: ClientProtocolException) {
                logger.error("There was an HTTP error while trying to fetch $url")
            }

            callback(status, syndFeed)
            logger.debug("started callback for $url")
        }

        private companion object {
            val MINIMUM_TIME_IN_BETWEEN = 20000L // 20 seconds

            val lastFetchTimes = hashMapOf<String, Stopwatch>()

            fun fetched(url: String) {
                val domain = getDomain(url)

                lastFetchTimes.put(domain, Stopwatch.createStarted())
            }

            fun canFetch(url: String): Boolean {
                val domain = getDomain(url)

                val lastTime = lastFetchTimes.get(domain) ?: return true

                return lastTime.elapsed(TimeUnit.MILLISECONDS) >= MINIMUM_TIME_IN_BETWEEN
            }

            fun getDomain(url: String): String = Regex("/.*").replace(Regex("http.*://").replace(url, ""), "")

        }
    }

    enum class FetchStatus {
        ERROR,
        SUCCESS
    }
}