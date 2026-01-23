package com.icl.surveillance.fhir

object ProgressHelper {

    fun calculateProgressPercentage(total: Int, completed: Int): Int? {
        return if (total > 0) {
            (completed * 100) / total
        } else {
            null
        }
    }
}
