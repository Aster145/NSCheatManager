package com.nscheatmanager.app.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nscheatmanager.app.R

const val QQ_GROUP_NUMBER = "457965140"
const val QQ_GROUP_URL = "https://qun.qq.com/universal-share/share?ac=1&authKey=fPqdvU2BW8s731iMkSW6OnVdc2ArUNe0ocLG%2FrbpMsEwJ4Ke1k7ksAmlkPkkMioj&busi_data=eyJncm91cENvZGUiOiI0NTc5NjUxNDAiLCJ0b2tlbiI6IkRRL1VKeG5BNmViMm9iRVVLTlUwYzVGK29nMG1IZGEyRWI0STh1TkszQ0NkeTdlTEtINTdqRUl3ZzJobGNNV0MiLCJ1aW4iOiIxNDUxMTc5NDgxIn0%3D&data=DbCHiE8dRZyXk6WkCg8btr6oOQrPK5vR_rCm0YXC5MrwseWitvCVjXfMvfh-qFBFJXSpAUVuzhIDT59CYoyWEA&svctype=4&tempid=h5_group_info"
const val PROJECT_GITHUB_URL = "https://github.com/Aster145/NSCheatManager"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    launchIntent: ((Intent) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val openIntent = launchIntent ?: remember(context) {
        { intent: Intent -> context.tryStartActivity(intent) }
    }
    var showNoHandler by remember { mutableStateOf(false) }
    var showGithubNoHandler by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "NS",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    stringResource(R.string.app_name),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.version_format, versionName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(R.string.about_purpose),
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            AboutCard(stringResource(R.string.open_source_credits)) {
                CreditRow("sys-botbase", stringResource(R.string.credit_sysbot))
                CreditRow("Atmosphère", stringResource(R.string.credit_atmosphere))
                CreditRow("Noexs", stringResource(R.string.credit_noexs))
                Text(
                    stringResource(R.string.license_gpl),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("github-link")
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_GITHUB_URL))
                        showGithubNoHandler = !openIntent(intent)
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Text("GH", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.project_github), style = MaterialTheme.typography.titleSmall)
                        Text(
                            PROJECT_GITHUB_URL,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("→", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (showGithubNoHandler) {
                Text(
                    stringResource(R.string.github_no_handler),
                    modifier = Modifier.testTag("github-link-error"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AboutCard(stringResource(R.string.usage_and_risk)) {
                Text(stringResource(R.string.authorized_devices_only), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.risk_warning),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("qq-link")
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL))
                        showNoHandler = !openIntent(intent)
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("QQ", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.join_qq_group), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.qq_group_number, QQ_GROUP_NUMBER),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (showNoHandler) {
                Text(
                    stringResource(R.string.qq_no_handler),
                    modifier = Modifier.testTag("qq-link-error"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Column(modifier = Modifier.padding(top = 6.dp), content = { content() })
        }
    }
}

@Composable
private fun CreditRow(name: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Text(
            description,
            modifier = Modifier.padding(start = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
        )
    }
}

private fun Context.tryStartActivity(intent: Intent): Boolean = try {
    if (intent.resolveActivity(packageManager) == null) {
        false
    } else {
        startActivity(intent)
        true
    }
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
