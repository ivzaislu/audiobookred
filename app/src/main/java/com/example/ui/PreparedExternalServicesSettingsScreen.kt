package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.ExternalServicesSettingsViewModel

@Composable
internal fun PreparedExternalServicesSettingsScreen(
    onBack: () -> Unit,
) {
    val vm: ExternalServicesSettingsViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val torrServeConnection by vm.torrServeConnection.collectAsStateWithLifecycle()

    var rutrackerLogin by rememberSaveable(settings.rutrackerLogin) {
        mutableStateOf(settings.rutrackerLogin)
    }
    var rutrackerPassword by rememberSaveable { mutableStateOf("") }
    var showRuTrackerPassword by rememberSaveable { mutableStateOf(false) }

    var torrServeUrl by rememberSaveable(settings.torrServeUrl) {
        mutableStateOf(settings.torrServeUrl)
    }
    var torrServeLogin by rememberSaveable(settings.torrServeLogin) {
        mutableStateOf(settings.torrServeLogin)
    }
    var torrServePassword by rememberSaveable { mutableStateOf("") }
    var showTorrServePassword by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item(key = "external-services-header") {
            AbredSubpageHeader(
                title = "RuTracker и TorrServe",
                subtitle = "Учётные данные источника и torrent-сервера",
                icon = Icons.Default.Link,
                onBack = onBack,
            )
        }

        item { AbredSettingsSectionTitle("RuTracker") }
        item {
            AbredSettingsCard {
                ServiceHeader(
                    title = "RuTracker",
                    subtitle = "Используется, если раздача или поиск требуют входа.",
                    icon = Icons.Default.Cloud,
                )

                Spacer(Modifier.height(AbredSpacing.Sm))
                OutlinedTextField(
                    value = rutrackerLogin,
                    onValueChange = { rutrackerLogin = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Логин") },
                )

                Spacer(Modifier.height(AbredSpacing.Xs))
                PasswordField(
                    value = rutrackerPassword,
                    onValueChange = { rutrackerPassword = it },
                    visible = showRuTrackerPassword,
                    onToggleVisible = { showRuTrackerPassword = !showRuTrackerPassword },
                    label = "Пароль",
                    placeholder = if (settings.rutrackerPasswordSaved) {
                        "Пароль сохранён"
                    } else {
                        "Введите пароль"
                    },
                )

                if (settings.rutrackerPasswordSaved) {
                    Text(
                        "Сохранённый пароль не показывается. Оставьте поле пустым, чтобы не менять его.",
                        modifier = Modifier.padding(top = AbredSpacing.Xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(AbredSpacing.Sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                ) {
                    Button(
                        onClick = {
                            vm.saveRuTracker(
                                login = rutrackerLogin,
                                newPassword = rutrackerPassword.takeIf(String::isNotBlank),
                            )
                            rutrackerPassword = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Сохранить")
                    }
                    if (settings.rutrackerPasswordSaved) {
                        TextButton(
                            onClick = {
                                vm.clearRuTrackerPassword()
                                rutrackerPassword = ""
                            },
                        ) {
                            Text("Удалить пароль")
                        }
                    }
                }
            }
        }

        item { AbredSettingsSectionTitle("TorrServe") }
        item {
            AbredSettingsCard {
                ServiceHeader(
                    title = "TorrServe",
                    subtitle = "Адрес сервера и HTTP-авторизация для torrent-воспроизведения.",
                    icon = Icons.Default.Link,
                )

                Spacer(Modifier.height(AbredSpacing.Sm))
                OutlinedTextField(
                    value = torrServeUrl,
                    onValueChange = { torrServeUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Адрес сервера") },
                    placeholder = { Text("http://192.168.1.10:8090") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                Spacer(Modifier.height(AbredSpacing.Xs))
                OutlinedTextField(
                    value = torrServeLogin,
                    onValueChange = { torrServeLogin = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Логин") },
                )

                Spacer(Modifier.height(AbredSpacing.Xs))
                PasswordField(
                    value = torrServePassword,
                    onValueChange = { torrServePassword = it },
                    visible = showTorrServePassword,
                    onToggleVisible = { showTorrServePassword = !showTorrServePassword },
                    label = "Пароль",
                    placeholder = if (settings.torrServePasswordSaved) {
                        "Пароль сохранён"
                    } else {
                        "Введите пароль"
                    },
                )

                if (settings.torrServePasswordSaved) {
                    Text(
                        "Сохранённый пароль не показывается. Оставьте поле пустым, чтобы не менять его.",
                        modifier = Modifier.padding(top = AbredSpacing.Xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(AbredSpacing.Sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                ) {
                    Button(
                        onClick = {
                            vm.saveTorrServe(
                                url = torrServeUrl,
                                login = torrServeLogin,
                                newPassword = torrServePassword.takeIf(String::isNotBlank),
                            )
                            torrServePassword = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Сохранить")
                    }
                    if (settings.torrServePasswordSaved) {
                        TextButton(
                            onClick = {
                                vm.clearTorrServePassword()
                                torrServePassword = ""
                            },
                        ) {
                            Text("Удалить пароль")
                        }
                    }
                }

                Spacer(Modifier.height(AbredSpacing.Xs))
                OutlinedButton(
                    onClick = {
                        vm.saveTorrServe(
                            url = torrServeUrl,
                            login = torrServeLogin,
                            newPassword = torrServePassword.takeIf(String::isNotBlank),
                        )
                        torrServePassword = ""
                        vm.checkTorrServe()
                    },
                    enabled = !torrServeConnection.checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (torrServeConnection.checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(AbredSpacing.Xs))
                        Text("Проверяем…")
                    } else {
                        Text("Сохранить и проверить подключение")
                    }
                }

                torrServeConnection.message?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = AbredSpacing.Xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (torrServeConnection.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}

@Composable
private fun ServiceHeader(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AbredSettingsIcon(icon)
        Spacer(Modifier.width(AbredSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    label: String,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Скрыть пароль" else "Показать пароль",
                )
            }
        },
    )
}
