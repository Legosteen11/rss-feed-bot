package io.github.legosteen11.rssfeedbot

import com.sun.syndication.feed.synd.SyndEntryImpl
import com.sun.syndication.feed.synd.SyndFeed
import com.sun.syndication.io.FeedException
import com.sun.syndication.io.SyndFeedInput
import com.sun.syndication.io.XmlReader
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import org.apache.commons.io.IOUtils
import org.apache.http.client.ClientProtocolException
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.telegram.telegrambots.api.methods.send.SendMessage
import java.io.IOException
import java.nio.charset.Charset
import java.util.*


object Users: IntIdTable() {
    val chatId = long("chat_id").uniqueIndex()
}

object Feeds: IntIdTable() {
    val resource = varchar("resource", 191)
    val type = enumeration("type", Feed.FeedType::class.java)
}

object Subscriptions: IntIdTable() {
    val user = reference("user", Users)
    val feed = reference("feed", Feeds)
}

object Posts: IntIdTable() {
    val feed = reference("feed", Feeds)
    val title = text("title")
    val url = varchar("url", 191)
    val publishedDate = datetime("publish_date").default(DateTime.now())

    // TODO: Store more data like author, source, hashtags etc.
}

class User(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<User>(Users) {
        suspend fun getOrCreate(chatId: Long): User {
            return transaction { User.find { Users.chatId eq chatId }.toList().firstOrNull() } // get user
                    ?: transaction { // or create new user
                User.new {
                    this.chatId = chatId
                } }
        }
    }

    var chatId by Users.chatId

    /**
     * Subscribe a user to a feed
     *
     * @param feed The feed to subscribe the user to
     */
    suspend fun subscribe(feed: Feed) {
        transaction {
            Subscription.new {
                this.feed = feed
                this.user = this@User
            }
        }
    }

    /**
     * Unsubscribe a user from a feed
     *
     * @param feed The feed to unsubscribe the user from
     */
    suspend fun unsubscribe(feed: Feed) {
        transaction {
            Subscription.find {
                Subscriptions.feed.eq(feed.id) and
                Subscriptions.user.eq(id)
            }.toList().firstOrNull()?.delete()
        }
    }

    /**
     * Get all subscriptions for this user
     *
     * @return An array with all the feeds that this user is subscribed to
     */
    suspend fun getSubscriptions(): Array<Feed> = transaction { Subscription.find { Subscriptions.user eq id }.toList().map { it.feed }.toTypedArray() } // get the user subscriptions then get the feeds for those subscriptions

    /**
     * Send a message to the user
     *
     * @param message The message to send to the user
     */
    suspend fun sendMessage(message: String) {
        Bot.execute(SendMessage(
                chatId,
                message
        ))
    }

    /**
     * Send an HTML enabled message
     *
     * @param message The message to send
     */
    suspend fun sendHtmlMessage(message: String) {
        Bot.execute(SendMessage(
                chatId,
                message
        ).enableHtml(true))
    }

    /**
     * Notify the user of a post
     *
     * @param post The post to notify the user of
     */
    suspend fun notifyOfPost(post: Post, feed: Feed) {
        sendHtmlMessage(
            """
                <b>${post.title}</b>
                Published at: ${post.publishedDate.toDateTimeString()}
                In feed ${feed.getNiceResource()}
                <a href="${post.url}">View post</a>
            """.trimIndent()
        )
    }
}

class Feed(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Feed>(Feeds) {
        /**
         * Checks whether this resource asnd type is parsable
         *
         * @param resource The resource string. This could be an url to a RSS feed or a Subreddit.
         * @param type The feed type
         *
         * @return True if parsable, false if not parsable
         */
        suspend fun parsable(resource: String, type: FeedType): Boolean {
            try {
                getFeed(getUrl(resource, type))
            } catch (e: Exception) {
                return false
            }
            return true
        }

        /**
         * Fetch the feed from the resource and return a SyndFeed object
         *
         * @return Returns a SyndFeed object of the feed.
         *
         * @throws IllegalArgumentException If the URI could not be parsed or if the feed type was not understood
         * @throws FeedException If the feed could not be parsed
         * @throws IOException If the connection was not successful
         * @throws ClientProtocolException If there was an HTTP protocol error
         */
        @Throws(IllegalArgumentException::class, FeedException::class, IOException::class, ClientProtocolException::class)
        suspend fun getFeed(url: String): SyndFeed {
            // TODO: Work with an queue so that there won't be any problems with requesting too many feeds.

            // from https://github.com/rometools/rome/issues/276
            HttpClients.createMinimal().use { client ->
                val request = HttpGet(url)
                request.addHeader("User-Agent", "telegram:io.github.legosteen11.rssfeedbot:v1.0.0 (by /u/LEGOSTEEN11)")
                client.execute(request).use { response ->
                    response.entity.content.use { stream ->
                        //println(IOUtils.toString(stream, Charset.defaultCharset()))
                        val xml = IOUtils.toString(stream, Charset.defaultCharset())

                        val input = SyndFeedInput()
                        return input.build(XmlReader(IOUtils.toInputStream(xml, Charset.defaultCharset())))
                    }
                }
            }
        }

        /**
         * Parses the type of feed based on the resource string.
         *
         * @param resource The resource string. This could be an url to a RSS feed or a Subreddit.
         *
         * @return The type of feed
         */
        fun parseType(resource: String): FeedType =
                if(resource.contains("reddit.com") || resource.startsWith("r/") || !resource.matches(Regex("http.*://.*")))
                    Feed.FeedType.SUBREDDIT
                else
                    Feed.FeedType.RSS

        /**
         * Converts a subreddit string or url to only the subreddit name and just returns a normal url
         *
         * @param resource The resource string. This could be an url to a RSS feed or a Subreddit
         *
         * @return The resource string: So the name of the subreddit or a URL to an RSS feed
         */
        fun parseResource(resource: String): String {
            if(parseType(resource) == FeedType.RSS)
                return resource

            return Regex(".*r/").replace(resource, "").replace("/.rss", "") // remove ...r/ and /.xml
        }

        /**
         * Get the RSS url for this resource and type.
         *
         * @param resource The resource string. This could be an url to a RSS feed or a Subreddit
         * @param type The type of feed
         *
         * @return The url for the RSS feed
         */
        fun getUrl(resource: String, type: FeedType): String = if(type == FeedType.SUBREDDIT) "https://www.reddit.com/r/$resource/.xml" else resource
    }

    var resource by Feeds.resource
    var type by Feeds.type

    /**
     * Get and create new posts in this feed. Will ignore old posts.
     *
     * @return Returns all the new posts.
     *
     * @throws IllegalArgumentException If the URI could not be parsed or if the feed type was not understood
     * @throws FeedException If the feed could not be parsed
     * @throws IOException If the connection was not successful
     * @throws ClientProtocolException If there was an HTTP protocol error
     */
    @Throws(IllegalArgumentException::class, FeedException::class, IOException::class, ClientProtocolException::class)
    suspend fun getAndCreateNewPosts(feed: SyndFeed? = null): Array<Post> {
        val syndFeed = feed ?: getFeed(getUrl(resource, type))

        val posts = arrayListOf<Post>()

        syndFeed.entries.forEach {
            it as SyndEntryImpl // cast to synd (RSS) feed object

            val postTitle = it.title
            val postUrl = it.link
            val postPublishedDate: Date? = it.publishedDate

            val exists = transaction { Post.find { // check whether the post already exists in the database
                Posts.url.eq(postUrl) and
                Posts.title.eq(postTitle) and
                Posts.feed.eq(this@Feed.id)
            }.toList() }.isNotEmpty()

            if(!exists) {
                posts.add( // add post to posts
                        transaction {
                            Post.new {
                                this.feed = this@Feed
                                title = postTitle
                                url = postUrl
                                publishedDate = postPublishedDate?.toDateTime() ?: DateTime.now()
                            }
                        }
                )
            }
        }

        return posts.toTypedArray()
    }

    /**
     * Get a nice resource string for the user to read.
     *
     * @return Returns r/subreddit or just the resource url
     */
    fun getNiceResource(): String = if(type == FeedType.SUBREDDIT) "r/$resource" else resource

    /**
     * Get the users that are subscribed to this feed.
     *
     * @return The users that are subscribed to this feed.
     */
    suspend fun getSubscribers(): Array<User> = transaction { Subscription.find { Subscriptions.feed eq id }.toList().map { it.user } }.toTypedArray()

    enum class FeedType {
        SUBREDDIT,
        RSS
    }
}

class Subscription(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Subscription>(Subscriptions)

    var user by User referencedOn Subscriptions.user
    var feed by Feed referencedOn Subscriptions.feed
}

class Post(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Post>(Posts)

    var feed by Feed referencedOn Posts.feed
    var title by Posts.title
    var url by Posts.url
    var publishedDate by Posts.publishedDate

    // TODO: Add some sort of template engine to let users make templates
}