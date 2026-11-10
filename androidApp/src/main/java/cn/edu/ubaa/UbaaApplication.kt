package cn.edu.ubaa

import android.app.Application
import cn.edu.ubaa.api.storage.initializeAndroidAccountStorage

class UbaaApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    initializeAndroidAccountStorage(this)
    cn.edu.ubaa.attendance.AttendanceScheduler.initialize(this)
  }
}
