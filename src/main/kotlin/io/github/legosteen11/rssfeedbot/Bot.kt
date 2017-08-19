package io.github.legosteen11.rssfeedbot

import com.sun.syndication.feed.synd.SyndFeed
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.jetbrains.exposed.sql.transactions.transaction
import org.telegram.telegrambots.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.api.methods.send.SendChatAction
import org.telegram.telegrambots.api.methods.send.SendMessage
import org.telegram.telegrambots.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.api.objects.Update
import org.telegram.telegrambots.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.bots.TelegramLongPollingBot

object Bot : TelegramLongPollingBot() {
    override fun getBotToken(): String = Config.bot_token

    override fun getBotUsername(): String = Config.bot_name

    override fun onUpdateReceived(update: Update) {
        if(update.hasMessage() && update.message.hasText()) {
            val chatId = update.message.chatId

            val (command, parameters) = update.message.text.replace("@$botUsername", "").split(" ").let { it.firstOrNull()?.replace("/", "") to it.subList(1, it.size) } // split by space and set command and parameters

            if(command.isNullOrBlank()) {
                launch(CommonPool) {
                    execute(SendChatAction(
                            chatId,
                            "Please provide a command"
                    ))
                }
                return
            }

            val loadingUser = async(CommonPool) { User.getOrCreate(chatId) }

            when(command) {
                "start" -> {
                    launch(CommonPool) {
                        execute(SendMessage(
                                chatId,
                                "With this bot you can subscribe to an RSS feed or subreddit and get messages when new posts are sent in that feed.\nTry subscribing to a subreddit with `/subscribe <subreddit or rss feed>`. You can even subscribe to multiple feeds at once: `/subscribe <feed> <feed> <...>`.\nFor example: `/subscribe r/pics r/news`."
                        ).enableMarkdown(true))
                    }
                }
                "subscribe" -> {
                    // subscribe to feeds

                    launch(CommonPool) {
                        val user = loadingUser.await()

                        val successList = arrayListOf<Feed>()
                        val failedList = arrayListOf<String>()

                        parameters.forEach { input ->
                            val resource = Feed.parseResource(input)
                            val type = Feed.parseType(input)

                            // get existing feed if it exists
                            val existingFeed = transaction { Feed.find { Feeds.resource eq resource }.toList().firstOrNull() }

                            if(existingFeed == null) {
                                if(type == Feed.FeedType.SUBREDDIT) {
                                    user.sendMessage("Waiting twenty seconds before subscribing to the next feed ($input) because it is a subreddit and we don't want to spam Reddit.")

                                    Thread.sleep(200000) // wait so that there won't be errors because of too many requests
                                }

                                try {
                                    val syndFeed = Feed.getFeed(Feed.getUrl(resource, type))

                                    // create new feed because it doesn't exist yet
                                    val newFeed = transaction {
                                        Feed.new {
                                            this.resource = resource
                                            this.type = type
                                        }
                                    }

                                    newFeed.getAndCreateNewPosts(syndFeed) // ignore the new posts as they will otherwise cause a lot of spam

                                    user.subscribe(newFeed) // subscribe user to feed

                                    user.sendMessage("Subscribed to ${newFeed.getNiceResource()}.")
                                } catch(e: Exception) {
                                    user.sendMessage("Could not subscribe to $input.")
                                }
                            } else {
                                user.subscribe(existingFeed) // subscribe user to feed

                                user.sendMessage("Subscribed to ${existingFeed.getNiceResource()}.")
                            }



                        }

                        if(successList.isEmpty())
                            user.sendMessage("Could not subscribe to feed(s).")
                        else
                            user.sendMessage("Subscribed to: ${successList.joinToString { it.getNiceResource() }}" +
                                    if (failedList.isNotEmpty()) ", but failed to subscribe to ${failedList.joinToString()}." else "." // message when failed to subscribe to some feeds
                            )
                    }
                }
                "unsubscribe" -> {
                    launch(CommonPool) {
                        val user = loadingUser.await()

                        execute(
                                SendMessage(
                                        chatId,
                                        "Click on the feed sources that you want to unsubscribe from."
                                ).setReplyMarkup(InlineKeyboardMarkup().setKeyboard( // list of unsubscribe buttons
                                        getUnsubscribeReplyMarkup(user)
                                ))
                        )
                    }
                }
                "format" -> {
                    launch(CommonPool) {
                        execute(SendChatAction(
                                chatId,
                                "This function is not yet available."
                        ))
                    }
                }
            }
        }

        if(update.hasCallbackQuery()) {
            val chatId = update.callbackQuery.message.chatId
            val messageId = update.callbackQuery.message.messageId
            val callbackQueryId = update.callbackQuery.id

            val loadingUser = async(CommonPool) { User.getOrCreate(chatId) }


            // update has callback query so it's probably from /unsubscribe
            val callback = update.callbackQuery.data

            if(callback.startsWith("unsubscribe_")) {
                val feedId = callback.replace("unsubscribe_", "").toIntOrNull()

                if(feedId == null) {
                    if(callback == "unsubscribe_cancel") {
                        launch(CommonPool) {
                            execute(DeleteMessage(chatId, messageId))
                        }

                        return
                    } else
                        return
                }

                launch(CommonPool) {
                    val user = loadingUser.await()

                    val feed = transaction { Feed.findById(feedId) } ?: return@launch

                    // unsubscribe user from feed
                    user.unsubscribe(feed)

                    execute(AnswerCallbackQuery().setCallbackQueryId(callbackQueryId).setText("Unsubscribed from ${feed.getNiceResource()}"))

                    //user.sendMessage("Unsubscribed from ${feed.getNiceResource()}")

                    // change inline keyboard
                    execute(EditMessageReplyMarkup().setChatId(chatId).setMessageId(messageId).setReplyMarkup(InlineKeyboardMarkup().setKeyboard(getUnsubscribeReplyMarkup(user))))
                }
            }
        }
    }
}

suspend fun getUnsubscribeReplyMarkup(user: User): List<List<InlineKeyboardButton>> {
    return user.getSubscriptions().map { listOf(InlineKeyboardButton(
            it.getNiceResource()
    ).setCallbackData("unsubscribe_${it.id}")) }.plus( // callback: unsubscribe_id
            listOf(listOf(InlineKeyboardButton("Cancel").setCallbackData("unsubscribe_cancel"))) // cancel button
    )
}