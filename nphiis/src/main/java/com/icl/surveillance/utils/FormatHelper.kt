package com.icl.surveillance.utils

import android.content.Context
import com.icl.surveillance.R
import java.lang.Double.parseDouble
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

class FormatHelper {

    fun isSameDay(dateOne: String, dateTwo: String): Boolean {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

        val date1 = sdf.parse(dateOne)
        val date2 = sdf.parse(dateTwo)

        if (date1 != null) {
            if (date1.equals(date2)) {
                return true
            }
        }
        return false
    }

    fun isSimilarDay(dateOne: String, dateTwo: String): Boolean {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)

        val date1 = sdf.parse(dateOne)
        val date2 = sdf.parse(dateTwo)

        if (date1 != null) {
            if (date1.equals(date2)) {
                return true
            }
        }
        return false
    }

    fun isLaterDay(dateOne: String, dateTwo: String): Boolean {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)

        val date1 = sdf.parse(dateOne)
        val date2 = sdf.parse(dateTwo)

        if (date1 != null) {
            if (date1.after(date2) || date1.equals(date2)) {
                return true
            }
        }
        return false
    }

    fun dateLessThanToday(valueDate: String): Boolean {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val today = getTodayDateNoTime()

        val date1 = sdf.parse(today)
        val date2 = sdf.parse(valueDate)

        if (date1 != null) {
            if (date1.equals(date2) || date1.after(date2)) {
                return true
            }
        }
        return false
    }

    fun dateTimeLessThanNow(date: String): Boolean {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val today = getTodayDate()

        val convertedDate = sdf.parse(date)

        val sdf2 = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val cool = convertedDate?.let { sdf2.format(it) }.toString()

        val date1 = sdf2.parse(today)
        val date2 = sdf2.parse(cool)

        if (date1 != null) {
            if (date1.equals(date2) || date1.after(date2)) {
                return true
            }
        }
        return false
    }

    fun checkDate(birthDate: String, d2: String): Boolean {

        val sdf1 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val currentdate = sdf1.parse(birthDate)

        val sdf2 = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val newCurrentDate = sdf2.format(currentdate)

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val date1 = sdf.parse(newCurrentDate)
        val date2 = sdf.parse(d2)

        // after() will return true if and only if date1 is after date 2
        if (date1.after(date2)) {
            return false
        }
        return true
    }

    fun checkDateTime(birthDate: String, d2: String): Boolean {

        val sdf1 = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val currentDate = sdf1.parse(birthDate)
        val newCurrentDate = sdf1.format(currentDate)

        val date1 = sdf1.parse(newCurrentDate)
        val date2 = sdf1.parse(d2)

        // after() will return true if and only if date1 is after date 2
        if (date1.after(date2)) {
            return false
        }
        return true
    }

    fun checkBirthDate(birthDate: String, d2: String): Boolean {

        val sdf1 = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
        val currentDate = sdf1.parse(birthDate)
        val newCurrentDate = sdf1.format(currentDate)
        val date1 = sdf1.parse(newCurrentDate)
        val date2 = sdf1.parse(d2)

        // after() will return true if and only if date1 is after date 2
        if (date1.after(date2)) {
            return false
        }
        return true
    }

    fun getBirthdayZone(date: String): String {
        val sourceFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)

        val convertedDate =
            try {
                sourceFormat.parse(date)
            } catch (e: Exception) {
                sourceFormat.parse(sourceFormat.format(Date()))
            }
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getTodayDate(): String {
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val date = Date()
        return formatter.format(date)
    }

    fun extractDateOnly(date: String): String {
        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy ", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun extractDateTime(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun extractTimeOnly(date: String): String {
        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("HH:mm", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun extractTimeOnlyAM(date: String): String {
        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("HH:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getTodayDateNoTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val date = Date()

        return sdf.format(date)
    }

    fun getDateNoTime(): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
        val date = Date()

        return sdf.format(date)
    }

    fun getYesterdayDateNoTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val date = Date()
        // subtract 1 day
        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.DATE, -1)
        val yesterday = cal.time
        return sdf.format(yesterday)
    }

    fun dateOfBirth(date: String): Date? {
        val destFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        return destFormat.parse(date)
    }

    fun convertStringDate(date: String): Date? {
        val destFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        return destFormat.parse(date)
    }

    fun convertStringDateAuto(date: String): Date? {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val convertedDate = sourceFormat.parse(date)
        return destFormat.parse(convertedDate?.let { destFormat.format(it) }.toString())
    }

    fun convertStringDateParent(date: String): Date? {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
        val convertedDate = sourceFormat.parse(date)
        return destFormat.parse(convertedDate?.let { destFormat.format(it) }.toString())
    }

    fun dateOfBirthCustom(date: String): Date? {
        val destFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
        return destFormat.parse(date)
    }

    fun getHour(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getRoundedHour(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
        val convertedDate = sourceFormat.parse(date)

        val calendar: Calendar = GregorianCalendar()
        calendar.time = convertedDate
        calendar[Calendar.MILLISECOND] = 0
        calendar[Calendar.SECOND] = 0
        val minutes = calendar[Calendar.MINUTE]
        /*
        if (minutes < 30) {
            calendar[Calendar.MINUTE] = 0
        } else {
            calendar[Calendar.MINUTE] = 30
        }*/
        val time = calendar.time
        return time.let { destFormat.format(it) }.toString()
    }

    fun getRoundedHourInterval(date: String): String {
        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
        val convertedDate = sourceFormat.parse(date)

        val calendar: Calendar = GregorianCalendar()
        calendar.time = convertedDate
        calendar[Calendar.MILLISECOND] = 0
        calendar[Calendar.SECOND] = 0
        val minutes = calendar[Calendar.MINUTE]
        /*
        if (minutes < 30) {
            calendar[Calendar.MINUTE] = 0
        } else {
            calendar[Calendar.MINUTE] = 30
        }*/
        val time = calendar.time
        return time.let { destFormat.format(it) }.toString()
    }

    fun getRoundedApproxHour(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
        val convertedDate = sourceFormat.parse(date)

        val calendar: Calendar = GregorianCalendar()
        calendar.time = convertedDate
        calendar[Calendar.MILLISECOND] = 0
        calendar[Calendar.SECOND] = 0
        val minutes = calendar[Calendar.MINUTE]
        if (minutes < 30) {
            calendar[Calendar.MINUTE] = 30
        } else {
            calendar[Calendar.MINUTE] = 60
        }
        val time = calendar.time
        return time.let { destFormat.format(it) }.toString()
    }

    fun getDateHour(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getSystemHour(date: String): String {
        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getSystemHourRounded(date: String): String {
        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)

        val calendar: Calendar = GregorianCalendar()
        calendar.time = convertedDate
        calendar[Calendar.MILLISECOND] = 0
        calendar[Calendar.SECOND] = 0
        calendar.add(Calendar.MINUTE, 5)
        val time = calendar.time
        return time.let { destFormat.format(it) }.toString()
    }

    fun getRoundedDateHour(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)

        val calendar: Calendar = GregorianCalendar()
        calendar.time = convertedDate
        calendar[Calendar.MILLISECOND] = 0
        calendar[Calendar.SECOND] = 0
        val minutes = calendar[Calendar.MINUTE]

        //        if (minutes < 30) {
        //            calendar[Calendar.MINUTE] = 0
        //        } else {2022-06-30
        //            calendar[Calendar.MINUTE] = 30
        //        }
        val time = calendar.time
        return time.let { destFormat.format(it) }.toString()
    }

    fun getRefinedDatePmAm(date: String): String {

        val sourceFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getRefinedDatePmAmEncounter(date: String): String {

        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun generateDate(date: String): Date {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        return sourceFormat.parse(date)
    }

    fun generateDateTime(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val date = Date()
        return formatter.format(date)
    }

    fun getHourRange(date: String): String {

        val calendar = Calendar.getInstance()
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)
        val date: Date = destFormat.parse(date)
        calendar.time = date
        calendar.add(Calendar.HOUR, -3)
        return destFormat.format(calendar.time)
    }

    fun getDateHourZone(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a", Locale.ENGLISH)

        val convertedDate =
            try {
                sourceFormat.parse(date)
            } catch (e: Exception) {
                sourceFormat.parse(sourceFormat.format(Date()))
            }
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun isWithinRange(systemTime: String, startTime: String, endTime: String): Boolean {
        try {
            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a")

            val starttime = simpleDateFormat.parse(startTime)
            val endtime = simpleDateFormat.parse(endTime)
            val current_time = simpleDateFormat.parse(systemTime)

            return if (current_time.after(endtime) && current_time.before(starttime)) {
                println("Yes it Matches ")
                true
            } else {
                println("No")
                false
            }
        } catch (e: ParseException) {
            e.printStackTrace()
        }
        return false
    }

    fun startCurrentEnd(min: String, actual: String, max: String): Boolean {
        try {
            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm a")

            val starttime = simpleDateFormat.parse(min)
            val endtime = simpleDateFormat.parse(max)
            val current_time = simpleDateFormat.parse(actual)

            return if (current_time.after(starttime) && current_time.before(endtime)) {
                println("Yes it Matches ")
                true
            } else {
                println("No")
                false
            }
        } catch (e: ParseException) {
            e.printStackTrace()
        }
        return false
    }

    fun startCurrentEndDates(min: String, actual: String, max: String): Boolean {
        try {
            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

            val starttime = simpleDateFormat.parse(min)
            val endtime = simpleDateFormat.parse(max)
            val current_time = simpleDateFormat.parse(actual)

            if (current_time != null) {
                return if ((current_time.after(starttime) || current_time.equals(starttime)) &&
                    (current_time.before(endtime) || current_time.equals(endtime))
                ) {
                    println("Yes it Matches ")
                    true
                } else {
                    println("No")
                    false
                }
            }
        } catch (e: ParseException) {
            e.printStackTrace()
        }
        return false
    }

    fun allowedDate(incoming: String): Boolean {

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            val dest = SimpleDateFormat("yyyy-MM-dd HH:mm")
            val currentDate = dest.format(Date())
            val incomingString = sdf.format(incoming.substring(0, 19))
            val startTime = dest.parse(currentDate)
            val endTime = dest.parse(incomingString)

            return if (startTime.after(endTime)) {
                println("Yes")
                true
            } else {
                println("No")
                false
            }
        } catch (e: ParseException) {
            e.printStackTrace()
        }
        return false
    }

    fun getHourNoExtension(date: String): String {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("hh", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getDayName(date: String): String {

        val sourceFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("EEEE", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getMonthName(date: String): String {

        val sourceFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("MMM", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getRefinedDate(date: String): String {

        val sourceFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getRefinedDateOnly(date: String): String {

        val sourceFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getSimpleDate(date: String): String {

        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun getSimpleReverseDate(date: String): String {

        val sourceFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun extractCareDateString(date: String): String {

        val sourceFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun extractCareTimeString(date: String): String {

        val sourceFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun extractDateString(date: String): String {

        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun extractTimeString(date: String): String {

        val sourceFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
        val destFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)

        val convertedDate = sourceFormat.parse(date)
        return convertedDate?.let { destFormat.format(it) }.toString()
    }

    fun checkPhoneNo(string: String): Boolean {
        var isNo = true

        try {
            val num = parseDouble(string)
        } catch (e: NumberFormatException) {
            isNo = false
        }
        return isNo
    }

    fun saveSharedPreference(context: Context, sharedKey: String, sharedValue: String) {

        val appName = context.getString(R.string.app_name)
        val sharedPreferences = context.getSharedPreferences(appName, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString(sharedKey, sharedValue)
        editor.apply()
    }

    fun retrieveSharedPreference(context: Context, sharedKey: String): String? {

        val appName = context.getString(R.string.app_name)

        val sharedPreferences = context.getSharedPreferences(appName, Context.MODE_PRIVATE)
        return sharedPreferences.getString(sharedKey, null)
    }

    fun formatDate(toString: String): String {

        val sdf1 = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH)
        return sdf1.format(toString)
    }
}
