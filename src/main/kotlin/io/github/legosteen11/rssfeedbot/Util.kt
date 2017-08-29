package io.github.legosteen11.rssfeedbot

import org.joda.time.DateTime
import java.util.*
import java.util.concurrent.TimeUnit

fun Date.toDateTime() = DateTime(this.time)

fun DateTime.toDateTimeString(): String = "${dayOfWeek().asShortText} ${dayOfMonth().asString} ${monthOfYear().asShortText} ${year().asString} at ${hourOfDay().asString}:${minuteOfHour().asString.let { if(it.length <= 1) "0$it" else it }}"

fun findPictureLinkInString(string: String): String? = string.split("\"", " ", "&").filter { Regex("http.*://.*[.](png|gif|jpg|jpeg)").matches(it) }.let { if(it.firstOrNull()?.contains("b.thumbs.redditmedia.com") == true && it.size > 1) it[1] else it.firstOrNull() }

fun isMuted() = Config.startTime.elapsed(TimeUnit.SECONDS) < Config.muteTimeInSeconds