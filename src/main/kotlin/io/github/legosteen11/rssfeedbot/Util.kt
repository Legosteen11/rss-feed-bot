package io.github.legosteen11.rssfeedbot

import org.joda.time.DateTime
import java.util.*

fun Date.toDateTime() = DateTime(this.time)

fun DateTime.toDateTimeString(): String = "${dayOfWeek().asShortText} ${dayOfMonth().asString} ${monthOfYear().asShortText} ${year().asString} at ${hourOfDay().asString}:${minuteOfHour().asString.let { if(it.length <= 1) "0$it" else it }}"