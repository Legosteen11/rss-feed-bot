package io.github.legosteen11.rssfeedbot

import com.sun.syndication.feed.synd.SyndCategoryImpl
import com.sun.syndication.feed.synd.SyndContentImpl
import com.sun.syndication.feed.synd.SyndEntryImpl
import com.sun.syndication.feed.synd.SyndFeed
import com.sun.syndication.io.FeedException
import com.sun.syndication.io.SyndFeedInput
import com.sun.syndication.io.XmlReader
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
import org.telegram.telegrambots.api.methods.send.SendPhoto
import org.telegram.telegrambots.exceptions.TelegramApiException
import java.io.IOException
import java.nio.charset.Charset
import java.util.*


object Users: IntIdTable() {
    val chatId = long("chat_id").uniqueIndex()
    val markup = text("markup").nullable()
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
    val categories = text("categories").nullable() // array of strings split by ;
    val author = varchar("author", 191).nullable()
    val pictureUrl = varchar("picture_url", 191).nullable() // url for picture (if exists in content string)
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
    var markup by Users.markup

    /**
     * Check whether the user is an admin or not
     *
     * @return Returns true if the user is an admin.
     */
    fun isAdmin() = Config.admin_ids.contains(chatId)

    /**
     * Subscribe a user to a feed
     *
     * @param feed The feed to subscribe the user to
     */
    suspend fun subscribe(feed: Feed) {
        val subscription = transaction { Subscription.find { Subscriptions.feed.eq(feed.id) and Subscriptions.user.eq(id) }.toList().firstOrNull() }

        if(subscription != null)
            return

        // create new subscription
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
    suspend fun notifyOfPost(post: Post, feed: Feed, customMarkup: String? = this.markup) {
        val markup = customMarkup ?:
            """
                <b>{title}</b>
                Published at: {date}
                In feed {feed}
                <a href="{url}">View post</a>
            """.trimIndent()

        val text = markup
                .replace("""\n""", "\n")
                .replace("{title}", post.title)
                .replace("{url}", post.url)
                .replace("{date}", post.publishedDate.toDateTimeString())
                .replace("{feed}", feed.getNiceResource())
                .replace("{author}", post.author ?: "someone at ${feed.getNiceResource()}")
                .replace("{categories}", (post.categories ?: "").split(";").joinToString { category ->
                    "${if(!category.contains(" ")) "#" else ""}$category"
                })

        if(markup.contains("{pic}")) {
            if(post.pictureUrl == null)
                return

            try {
                Bot.sendPhoto(SendPhoto().setChatId(chatId).setPhoto(post.pictureUrl).setCaption(markup.replace("{pic}", "")))
            } catch (e: TelegramApiException) {
                logger.info("Unable to send photo with url ${post.pictureUrl} in feed ${feed.getNiceResource()}")
            }
            return
        }

        try {
            sendHtmlMessage(
                    text
            )
        } catch (e: Exception) {
            logger.error("Exception while trying to send a post!", e)
        }
    }
}

class Feed(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Feed>(Feeds) {
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

        syndFeed.entries.forEach { entry ->
            entry as SyndEntryImpl // cast to synd (RSS) feed object

            val postTitle = entry.title
            val postUrl = entry.link
            val postPublishedDate: Date? = entry.publishedDate

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
                                author = entry.author
                                categories = entry.categories.joinToString(";") { (it as SyndCategoryImpl).name }
                                pictureUrl = findPictureLinkInString(entry.contents.joinToString("\n"))
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

    suspend fun notifySubscribersOfNewPosts(feed: SyndFeed) {
        val users = getSubscribers()
        val posts = getAndCreateNewPosts(feed)

        posts.forEach { post ->
            users.forEach { user ->
                user.notifyOfPost(post, this)
            }
        }
    }

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
    var categories by Posts.categories
    var author by Posts.author
    var pictureUrl by Posts.pictureUrl
}