package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = AdminApplication::class)
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Prophecy Verse Admin", appName)
  }

  @Test
  fun `launch MainActivity without crash`() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    org.junit.Assert.assertNotNull(controller.get())
  }

  @Test
  fun `verify mainNavItems has no null items and routes are valid`() {
    val items = com.example.navigation.AdminNavDestination.mainNavItems
    org.junit.Assert.assertTrue("mainNavItems should not be empty", items.isNotEmpty())
    items.forEach { destination ->
      org.junit.Assert.assertNotNull("Destination should not be null", destination)
      org.junit.Assert.assertNotNull("Destination route should not be null", destination.route)
      org.junit.Assert.assertTrue("Destination route should not be blank", destination.route.isNotBlank())
    }
  }
}
