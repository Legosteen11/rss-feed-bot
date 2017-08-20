package io.github.legosteen11.rssfeedbot

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.TelegramBotsApi

val logger = LoggerFactory.getLogger("rss-feed-bot")!!

fun main(args: Array<String>) {
    val username = args.getOrNull(0) ?: "development"
    val password = args.getOrNull(1) ?: "development"
    val database = args.getOrNull(2) ?: "development"

    // connect to the database
    Database.connect("jdbc:mysql://localhost/$database?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC&nullNamePatternMatchesAll=true", driver = "com.mysql.cj.jdbc.Driver", user = username, password = password)

    // create the tables
    transaction {
        logger.addLogger(StdOutSqlLogger)

        SchemaUtils.create(Users, Feeds, Subscriptions, Posts)
    }

    ApiContextInitializer.init()

    val botsApi = TelegramBotsApi()

    botsApi.registerBot(Bot)

    logger.info("Adding feeds to queue")
    addFeedsToQueue() // add existing feeds to the queue
    RSSQueue.start() // start the queue
    logger.info("Started queue")
}

val WAIT_BETWEEN_POSTS_TIME = 20000L // wait 20 secs before fetching the next feed

fun addFeedsToQueue() {
    val feeds = transaction { Feed.all().toList() }

    feeds.forEach { feed ->
        RSSQueue.addFeedToQueue(feed)
    }
}