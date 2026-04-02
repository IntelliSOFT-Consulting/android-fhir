package com.icl.surveillance.network

import java.io.IOException

class NoNetworkException(
    message: String = "No internet connection available"
) : IOException(message)
