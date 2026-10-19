package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredStroke

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuTrackerSettingsCard(
    login: String,
    onLoginChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    passwordSaved: Boolean,
    onSave: () -> Unit,
    onClearPassword: () -> Unit,
) {
    AbredInnerSettingsCard {
        ExternalServiceHeader(
            subtitle = "Используется, если раздача или поиск требуют входа.",
        )

        Spacer(Modifier.height(AbredSpacing.Sm))
        OutlinedTextField(
            value = login,
            onValueChange = onLoginChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Логин") },
        )

        Spacer(Modifier.height(AbredSpacing.Xs))
        ExternalPasswordField(
            value = password,
            onValueChange = onPasswordChange,
            visible = passwordVisible,
            onToggleVisible = onTogglePasswordVisible,
            label = "Пароль",
            placeholder = if (passwordSaved) "Пароль сохранён" else "Введите пароль",
        )

        if (passwordSaved) {
            SavedPasswordHint()
        }

        Spacer(Modifier.height(AbredSpacing.Sm))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs),
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("Сохранить")
            }
            if (passwordSaved) {
                TextButton(onClick = onClearPassword) {
                    Text("Удалить пароль")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TorrServeSettingsCard(
    url: String,
    onUrlChange: (String) -> Unit,
    login: String,
    onLoginChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    passwordSaved: Boolean,
    connectionChecking: Boolean,
    connectionMessage: String?,
    connectionIsError: Boolean,
    onSave: () -> Unit,
    onClearPassword: () -> Unit,
    onSaveAndCheck: () -> Unit,
) {
    AbredInnerSettingsCard {
        ExternalServiceHeader(
            subtitle = "Адрес сервера и HTTP-авторизация для torrent-воспроизведения.",
        )

        Spacer(Modifier.height(AbredSpacing.Sm))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Адрес сервера") },
            placeholder = { Text("http://192.168.1.10:8090") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        Spacer(Modifier.height(AbredSpacing.Xs))
        OutlinedTextField(
            value = login,
            onValueChange = onLoginChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Логин") },
        )

        Spacer(Modifier.height(AbredSpacing.Xs))
        ExternalPasswordField(
            value = password,
            onValueChange = onPasswordChange,
            visible = passwordVisible,
            onToggleVisible = onTogglePasswordVisible,
            label = "Пароль",
            placeholder = if (passwordSaved) "Пароль сохранён" else "Введите пароль",
        )

        if (passwordSaved) {
            SavedPasswordHint()
        }

        Spacer(Modifier.height(AbredSpacing.Sm))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs),
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
            ) {
                Text("Сохранить")
            }
            if (passwordSaved) {
                TextButton(onClick = onClearPassword) {
                    Text("Удалить пароль")
                }
            }
        }

        Spacer(Modifier.height(AbredSpacing.Xs))
        OutlinedButton(
            onClick = onSaveAndCheck,
            enabled = !connectionChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (connectionChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.height(AbredSizes.IconSmall),
                    strokeWidth = AbredStroke.Spinner,
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text("Проверяем…")
            } else {
                Text("Сохранить и проверить подключение")
            }
        }

        connectionMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(top = AbredSpacing.Xs),
                style = MaterialTheme.typography.bodySmall,
                color = if (connectionIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun ExternalServiceHeader(subtitle: String) {
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SavedPasswordHint() {
    Text(
        "Сохранённый пароль не показывается. Оставьте поле пустым, чтобы не менять его.",
        modifier = Modifier.padding(top = AbredSpacing.Xs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExternalPasswordField(
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

