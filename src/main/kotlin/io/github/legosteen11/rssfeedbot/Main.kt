package io.github.legosteen11.rssfeedbot

import com.sun.syndication.io.FeedException
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import org.apache.http.client.ClientProtocolException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.TelegramBotsApi
import java.io.IOException
import java.util.logging.Logger

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
        println(Feed.getFeed(Feed.getUrl(feed.resource, feed.type)))

        feed.getAndCreateNewPosts().let {
            it.forEach {
                println("Title: ${it.title}, url: ${it.url} @ ${it.publishedDate}")
            }
        }
    }
    */

    ApiContextInitializer.init()

    val botsApi = TelegramBotsApi()

    botsApi.registerBot(Bot)

    launch(CommonPool) {
        while (true) {
            println("sending new posts")

            // update the feed
            notifyUsersOfNewPosts()
            println("sent new posts")

            Thread.sleep(600000) // update every 600.000 seconds (10 minutes)
        }
    }
}

suspend fun notifyUsersOfNewPosts() {
    val feeds = transaction { Feed.all().toList() }

    feeds.forEach { feed ->
        try {
            val newPosts = feed.getAndCreateNewPosts()

            val subscribers = feed.getSubscribers()

            subscribers.forEach { user ->
                newPosts.forEach { post ->
                    user.notifyOfPost(post)
                }
            }
        } catch (e: IOException) {
            println("IO Exception while trying to fetch ${feed.getNiceResource()}")
        } catch (e: IllegalArgumentException) {
            println("Feed type or url was not understood for ${feed.getNiceResource()}")
        } catch (e: FeedException) {
            println("The feed ${feed.getNiceResource()} could not be parsed")
        } catch (e: ClientProtocolException) {
            println("There was an HTTP error while trying to fetch ${feed.getNiceResource()}")
        }

    }
}