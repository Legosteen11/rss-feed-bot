package io.github.legosteen11.rssfeedbot

import com.sun.syndication.io.FeedException
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import org.apache.http.client.ClientProtocolException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.TelegramBotsApi
import java.io.IOException

val logger = LoggerFactory.getLogger("rss-feed-bot")!!

fun main(args: Array<String>) {
    // connect to the database
    Database.connect("jdbc:mysql://localhost/development?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC&nullNamePatternMatchesAll=true", driver = "com.mysql.cj.jdbc.Driver", user = "development", password = "development")

    // create the tables
    transaction {
        logger.addLogger(StdOutSqlLogger)

        SchemaUtils.create(Users, Feeds, Subscriptions, Posts)
    }

    /*
    val feed = transaction {
        Feed.new {
            resource = "pics"
            type = Feed.FeedType.SUBREDDIT
        }
    }

    launch(CommonPool) {
        logger.info(Feed.getFeed(Feed.getUrl(feed.resource, feed.type)))

        feed.getAndCreateNewPosts().let {
            it.forEach {
                logger.info("Title: ${it.title}, url: ${it.url} @ ${it.publishedDate}")
            }
        }
    }
    */

    ApiContextInitializer.init()

    val botsApi = TelegramBotsApi()

    botsApi.registerBot(Bot)

    launch(CommonPool) {
        while (true) {
            logger.info("sending new posts")

            // update the feed
            notifyUsersOfNewPosts()
            logger.info("sent new posts")

            Thread.sleep(REFRESH_TIME)
        }
    }
}

val WAIT_BETWEEN_POSTS_TIME = 20000L // wait 20 secs before fetching the next feed
val REFRESH_TIME = 600000L // update every 600.000 seconds (10 minutes)

suspend fun notifyUsersOfNewPosts() {
    val feeds = transaction { Feed.all().toList() }

    feeds.forEach { feed ->
        try {
            logger.info("Loading feed ${feed.getNiceResource()}")

            val newPosts = feed.getAndCreateNewPosts()

            val subscribers = feed.getSubscribers()

            subscribers.forEach { user ->
                newPosts.forEach { post ->
                    user.notifyOfPost(post, feed)
                }
            }

            logger.info("Done loading feed ${feed.getNiceResource()}")

            Thread.sleep(WAIT_BETWEEN_POSTS_TIME)
        } catch (e: IOException) {
            logger.error("IO Exception while trying to fetch ${feed.getNiceResource()}")
        } catch (e: IllegalArgumentException) {
            logger.error("Feed type or url was not understood for ${feed.getNiceResource()}")
        } catch (e: FeedException) {
            logger.error("The feed ${feed.getNiceResource()} could not be parsed")
        } catch (e: ClientProtocolException) {
            logger.error("There was an HTTP error while trying to fetch ${feed.getNiceResource()}")
        }

    }
}