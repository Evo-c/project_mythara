package com.mythara

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry. The only reason this exists at M0 is to anchor Hilt's
 * generated component (DaggerMytharaApp_HiltComponents) so every later
 * milestone can `@HiltAndroidApp` graph things off it without churn.
 */
@HiltAndroidApp
class MytharaApp : Application()
