package tk.glucodata.ui.overlay

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.journal.JournalQuickEntryActivity
import tk.glucodata.ui.journal.journalTypeColor

/**
 * "+ Insulin" and "+ Food" for the floating glucose's details card: each opens the journal's
 * entry sheet on that type over whatever is on screen ([openFloatingQuickEntry]). Drawn in the
 * card's own text colour, so it sits in either theme.
 */
@Composable
fun FloatingQuickEntryButtons(
    textColor: Color,
    onLog: (JournalEntryType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FloatingQuickEntryButton(
            type = JournalEntryType.INSULIN,
            labelRes = R.string.journal_type_insulin,
            descriptionRes = R.string.journal_quick_log_insulin,
            textColor = textColor,
            onLog = onLog
        )
        FloatingQuickEntryButton(
            type = JournalEntryType.CARBS,
            labelRes = R.string.journal_type_food,
            descriptionRes = R.string.journal_quick_log_food,
            textColor = textColor,
            onLog = onLog
        )
    }
}

/** What the floating glucose service does with a tap on one of the buttons. */
fun openFloatingQuickEntry(context: Context, type: JournalEntryType) {
    JournalQuickEntryActivity.start(context, type)
}

@Composable
private fun RowScope.FloatingQuickEntryButton(
    type: JournalEntryType,
    labelRes: Int,
    descriptionRes: Int,
    textColor: Color,
    onLog: (JournalEntryType) -> Unit
) {
    val description = stringResource(descriptionRes)
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = textColor.copy(alpha = 0.08f),
        shape = shape,
        modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onLog(type) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = journalTypeColor(type),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(labelRes),
                color = textColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
