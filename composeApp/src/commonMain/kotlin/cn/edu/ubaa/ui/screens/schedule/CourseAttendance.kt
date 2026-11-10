package cn.edu.ubaa.ui.screens.schedule

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.ubaa.attendance.AttendanceRecord
import cn.edu.ubaa.attendance.AttendanceStore
import cn.edu.ubaa.repository.ScheduleStore

@Composable
internal fun rememberAttendanceRecords(dates: List<String>): List<AttendanceRecord> {
  val revision by AttendanceStore.changes.collectAsState()
  val accountRevision by ScheduleStore.changes.collectAsState()
  val owner = remember(accountRevision) { ScheduleStore.account() }
  return remember(owner, revision, dates) {
    dates.flatMap { date -> owner?.let { AttendanceStore.records(it, date) }.orEmpty() }
  }
}

@Composable
internal fun CourseAttendanceBadge(status: String?, onColor: Color? = null) {
  val color =
      onColor
          ?: when (status) {
            "success" -> MaterialTheme.colorScheme.primary
            "failed" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          }
  Surface(
      color = color.copy(alpha = 0.14f),
      contentColor = color,
      shape = RoundedCornerShape(4.dp),
  ) {
    Text(
        text =
            when (status) {
              "success" -> "已签到"
              "failed" -> "签到失败"
              "pending" -> "待签到"
              else -> "暂无记录"
            },
        modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
    )
  }
}
