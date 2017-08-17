package io.github.legosteen11.rssfeedbot

import org.joda.time.DateTime
import java.util.*

fun Date.toDateTime() = DateTime(this.time)

fun DateTime.toDateTimeString(): String = "${year()}-${monthOfYear()}-${dayOfMonth()} ${hourOfDay()}:${minuteOfHour()}"