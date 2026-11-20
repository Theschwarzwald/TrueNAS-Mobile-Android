package com.example.truenasapp.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.truenasapp.Graph
import com.example.truenasapp.data.CatalogApp
import com.example.truenasapp.data.FileEntry
import com.example.truenasapp.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ===================================================================== opening the form

/** Opens the full "customize before installing" form, like the desktop install wizard. */
object InstallFormController {
    class Request(val app: CatalogApp, val name: String, val onInstalled: () -> Unit)

    internal val open = MutableStateFlow<Request?>(null)

    fun show(app: CatalogApp, name: String, onInstalled: () -> Unit) {
        open.value = Request(app, name, onInstalled)
    }

    fun close() {
        open.value = null
    }
}

/** Put once near the top of the UI (it covers the whole screen while open). */
@Composable
fun InstallFormHost() {
    val req by InstallFormController.open.collectAsStateWithLifecycle()
    req?.let { r -> key(r) { InstallFormScreen(r, onClose = { InstallFormController.close() }) } }
}

// ===================================================================== the schema

/** One question from the app's install schema (the same ones the desktop wizard asks). */
internal class Field(
    val variable: String,
    val label: String,
    val description: String,
    val group: String?,
    val type: String,
    val required: Boolean,
    val nullable: Boolean,
    val private: Boolean,
    val hidden: Boolean,
    val editable: Boolean,
    val default: Any?,
    val hasDefault: Boolean,
    val enum: List<Pair<Any?, String>>,
    val min: Double?,
    val max: Double?,
    val minLength: Int?,
    val maxLength: Int?,
    val validChars: Regex?,
    val showIf: List<Triple<String, String, Any?>>,
    val attrs: List<Field>,
    val items: List<Field>,
)

private fun parseField(o: JSONObject): Field {
    val s = o.optJSONObject("schema") ?: JSONObject()
    val enumArr = s.optJSONArray("enum")
    val showIfArr = s.optJSONArray("show_if")
    return Field(
        variable = o.optString("variable"),
        label = o.optString("label").ifBlank { o.optString("variable") },
        description = if (o.isNull("description")) "" else o.optString("description"),
        group = o.optString("group").ifBlank { null },
        type = s.optString("type", "string"),
        required = s.optBoolean("required", false),
        nullable = s.optBoolean("null", false),
        private = s.optBoolean("private", false),
        hidden = s.optBoolean("hidden", false),
        editable = s.optBoolean("editable", true),
        default = fromJson(s.opt("default")),
        hasDefault = s.has("default"),
        enum = (0 until (enumArr?.length() ?: 0)).mapNotNull { i ->
            val e = enumArr!!.optJSONObject(i) ?: return@mapNotNull null
            val v = fromJson(e.opt("value"))
            v to (if (e.isNull("description")) "" else e.optString("description")).ifBlank { v?.toString() ?: "None" }
        },
        min = if (s.has("min")) s.optDouble("min") else null,
        max = if (s.has("max")) s.optDouble("max") else null,
        minLength = if (s.has("min_length")) s.optInt("min_length") else null,
        maxLength = if (s.has("max_length")) s.optInt("max_length") else null,
        validChars = s.optString("valid_chars").takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() },
        showIf = (0 until (showIfArr?.length() ?: 0)).mapNotNull { i ->
            val c = showIfArr!!.optJSONArray(i) ?: return@mapNotNull null
            Triple(c.optString(0), c.optString(1), fromJson(c.opt(2)))
        },
        attrs = s.optJSONArray("attrs").fields(),
        items = s.optJSONArray("items").fields(),
    )
}

private fun JSONArray?.fields(): List<Field> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(::parseField) }

/** JSON to plain Kotlin values: Map, List, String, Long, Double, Boolean or null. */
private fun fromJson(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> LinkedHashMap<String, Any?>().apply { v.keys().forEach { put(it, fromJson(v.opt(it))) } }
    is JSONArray -> (0 until v.length()).map { fromJson(v.opt(it)) }
    is Int -> v.toLong()
    is Float -> v.toDouble()
    else -> v
}

private val TEXT_TYPES = setOf("string", "text", "hostpath", "path", "ipaddr", "uri", "password", "cron")

@Suppress("UNCHECKED_CAST")
private fun defaultValue(f: Field): Any? = when (f.type) {
    "dict" -> {
        val base = f.default as? Map<String, Any?> ?: emptyMap()
        LinkedHashMap<String, Any?>().apply {
            f.attrs.forEach { a -> put(a.variable, if (base.containsKey(a.variable)) base[a.variable] else defaultValue(a)) }
        }
    }
    "list" -> (f.default as? List<Any?>) ?: emptyList<Any?>()
    "boolean" -> (f.default as? Boolean) ?: if (f.nullable && f.hasDefault) null else false
    "int", "float" -> f.default
    else -> when {
        f.default != null -> f.default
        f.nullable -> null
        f.enum.isNotEmpty() && f.required -> f.enum.first().first
        else -> ""
    }
}

private fun looseEquals(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    else -> a == b
}

private fun visible(f: Field, siblings: Map<String, Any?>): Boolean {
    if (f.hidden) return false
    return f.showIf.all { (variable, op, expected) ->
        val actual = siblings[variable]
        when (op) {
            "=" -> looseEquals(actual, expected)
            "!=" -> !looseEquals(actual, expected)
            "in" -> (expected as? List<*>)?.any { looseEquals(it, actual) } ?: false
            "nin", "notin" -> (expected as? List<*>)?.none { looseEquals(it, actual) } ?: true
            ">", ">=", "<", "<=" -> {
                val x = (actual as? Number)?.toDouble()
                val y = (expected as? Number)?.toDouble()
                if (x == null || y == null) false else when (op) {
                    ">" -> x > y
                    ">=" -> x >= y
                    "<" -> x < y
                    else -> x <= y
                }
            }
            else -> true
        }
    }
}

/** Returns [root] with the value at [path] replaced (path = map keys and list indexes). */
@Suppress("UNCHECKED_CAST")
private fun setIn(root: Any?, path: List<Any>, v: Any?): Any? {
    if (path.isEmpty()) return v
    return when (val head = path.first()) {
        is String -> LinkedHashMap((root as? Map<String, Any?>) ?: emptyMap()).also { it[head] = setIn(it[head], path.drop(1), v) }
        is Int -> ((root as? List<Any?>) ?: emptyList()).toMutableList().also { if (head in it.indices) it[head] = setIn(it[head], path.drop(1), v) }
        else -> root
    }
}

private fun pathKey(path: List<Any>) = path.joinToString(".")

/** Checks the visible fields the way the server will. Returns "path" -> problem. */
@Suppress("UNCHECKED_CAST")
private fun validate(f: Field, value: Any?, siblings: Map<String, Any?>, path: List<Any>, out: MutableMap<String, String>) {
    if (!visible(f, siblings)) return
    val key = pathKey(path)
    when (f.type) {
        "dict" -> {
            val m = value as? Map<String, Any?> ?: emptyMap()
            f.attrs.forEach { a -> validate(a, m[a.variable], m, path + a.variable, out) }
        }
        "list" -> {
            val item = f.items.firstOrNull() ?: return
            (value as? List<Any?>).orEmpty().forEachIndexed { i, v ->
                validate(item, v, (v as? Map<String, Any?>) ?: emptyMap(), path + i, out)
            }
            if (f.required && (value as? List<*>).isNullOrEmpty()) out[key] = "Add at least one"
        }
        "int", "float" -> when {
            value is String -> out[key] = if (f.type == "int") "Enter a whole number" else "Enter a number"
            value == null -> if (f.required) out[key] = "Required"
            value is Number -> {
                val d = value.toDouble()
                if (f.min != null && d < f.min) out[key] = "Must be at least ${fmt(f.min)}"
                else if (f.max != null && d > f.max) out[key] = "Must be at most ${fmt(f.max)}"
            }
        }
        "boolean" -> Unit
        else -> {
            val s = value?.toString() ?: ""
            when {
                s.isBlank() -> if (f.required) out[key] = "Required"
                f.minLength != null && s.length < f.minLength -> out[key] = "At least ${f.minLength} characters"
                f.maxLength != null && s.length > f.maxLength -> out[key] = "At most ${f.maxLength} characters"
                f.validChars != null && !f.validChars.matches(s) -> out[key] = "Contains characters that aren't allowed"
            }
        }
    }
}

private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

private class Group(val name: String, val description: String, val fields: List<Field>)

private class Form(val groups: List<Group>, val fields: List<Field>)

private fun parseForm(schema: JSONObject): Form {
    val fields = schema.optJSONArray("questions").fields()
    val declared = schema.optJSONArray("groups")
    val order = (0 until (declared?.length() ?: 0)).mapNotNull { declared!!.optJSONObject(it) }
        .map { it.optString("name") to (if (it.isNull("description")) "" else it.optString("description")) }
    val names = order.map { it.first }
    val groups = order.map { (n, d) -> Group(n, d, fields.filter { it.group == n }) }.toMutableList()
    val loose = fields.filter { it.group == null || it.group !in names }
    if (loose.isNotEmpty()) groups += Group("Other", "", loose)
    return Form(groups.filter { it.fields.isNotEmpty() }, fields)
}

// ===================================================================== the screen

private val validName = Regex("^[a-z]([-a-z0-9]*[a-z0-9])?$")

@Suppress("UNCHECKED_CAST")
@Composable
private fun InstallFormScreen(req: InstallFormController.Request, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = req.app
    var form by remember { mutableStateOf<Form?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var values by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var name by remember { mutableStateOf(req.name) }
    var showErrors by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        loadError = null
        try {
            val f = parseForm(Graph.api.appInstallSchema(app))
            values = LinkedHashMap<String, Any?>().apply { f.fields.forEach { put(it.variable, defaultValue(it)) } }
            f.groups.firstOrNull()?.let { expanded[it.name] = true }
            form = f
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = friendlyError(e)
        }
    }

    val f = form
    val errors = remember(values, f) {
        mutableMapOf<String, String>().also { out -> f?.fields?.forEach { validate(it, values[it.variable], values, listOf(it.variable), out) } }
    }
    val nameOk = name.length in 1..40 && validName.matches(name)

    BackHandler(enabled = !installing) { if (f != null) confirmLeave = true else onClose() }

    fun update(path: List<Any>, v: Any?) {
        values = setIn(values, path, v) as Map<String, Any?>
    }

    fun install() {
        val form0 = f ?: return
        showErrors = true
        if (!nameOk || errors.isNotEmpty()) {
            // Open the sections that need attention.
            val bad = errors.keys.map { it.substringBefore('.') }.toSet()
            form0.groups.filter { g -> g.fields.any { it.variable in bad } }.forEach { expanded[it.name] = true }
            Toast.makeText(context, "${errors.size + if (nameOk) 0 else 1} field(s) need attention", Toast.LENGTH_LONG).show()
            scope.launch { listState.animateScrollToItem(0) }
            return
        }
        installing = true
        installError = null
        val appName = name
        val payload = values
        Graph.appScope.launch {
            try {
                Graph.api.installApp(appName, app, payload)
                Toast.makeText(context.applicationContext, "${app.title} installed", Toast.LENGTH_LONG).show()
                req.onInstalled()
                onClose()
            } catch (e: Exception) {
                installError = friendlyError(e)
                scope.launch { listState.animateScrollToItem(0) }
            }
            installing = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (f != null) confirmLeave = true else onClose() }, enabled = !installing) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                AppIcon(app.iconUrl, app.title, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Install ${app.title}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(app.version.takeIf { it.isNotBlank() }?.let { "Version $it" }, "${app.train} train").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (installing) LinearProgressIndicator(Modifier.fillMaxWidth())

            when {
                loadError != null -> Column(Modifier.padding(16.dp)) {
                    ErrorCard(loadError!!) { reload++ }
                    TextButton(onClick = {
                        onClose()
                        ExpertController.ask("/ui/apps/available/${app.train}/${app.name}/install")
                    }) { Text("Set up in Expert mode instead") }
                }
                f == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text("Loading the install options…")
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    installError?.let { msg ->
                        item {
                            Card(
                                Modifier.readableWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("TrueNAS couldn't install it", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.size(4.dp))
                                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.readableWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Application name", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it.lowercase() },
                                    singleLine = true,
                                    enabled = !installing,
                                    isError = !nameOk,
                                    supportingText = { Text(if (nameOk) "The name it gets on your NAS" else "Lowercase letters, numbers and dashes") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    items(f.groups, key = { it.name }) { g ->
                        val open = expanded[g.name] == true
                        val shown = g.fields.filter { visible(it, values) }
                        if (shown.isEmpty()) return@items
                        val groupErrors = errors.keys.count { k -> g.fields.any { k == it.variable || k.startsWith(it.variable + ".") } }
                        Card(Modifier.readableWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable { expanded[g.name] = !open }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (showErrors && groupErrors > 0) {
                                        Text("$groupErrors to fix", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    } else if (g.description.isNotBlank() && !open) {
                                        Text(g.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                }
                                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Collapse" else "Expand")
                            }
                            if (open) {
                                Column(
                                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (g.description.isNotBlank()) {
                                        Text(g.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    shown.forEach { field ->
                                        FieldEditor(
                                            field, values[field.variable], values, listOf(field.variable),
                                            onChange = ::update, errors = errors, showErrors = showErrors, enabled = !installing, depth = 0,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (f != null) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (installing) {
                        Text(
                            "Installing… this can take a few minutes while it downloads.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TextButton(onClick = {
                            onClose()
                            ExpertController.ask("/ui/apps/available/${app.train}/${app.name}/install")
                        }) { Text("Expert mode") }
                        Spacer(Modifier.weight(1f))
                    }
                    Button(onClick = ::install, enabled = !installing) { Text("Install") }
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard these settings?") },
            text = { Text("${app.title} won't be installed.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }
}

// ===================================================================== one field

@Suppress("UNCHECKED_CAST")
@Composable
private fun FieldEditor(
    f: Field,
    value: Any?,
    siblings: Map<String, Any?>,
    path: List<Any>,
    onChange: (List<Any>, Any?) -> Unit,
    errors: Map<String, String>,
    showErrors: Boolean,
    enabled: Boolean,
    depth: Int,
) {
    if (!visible(f, siblings)) return
    val key = pathKey(path)
    val error = errors[key]?.takeIf { showErrors || (f.type == "int" && value is String) }
    val label = if (f.required) "${f.label} *" else f.label
    val canEdit = enabled && f.editable

    when (f.type) {
        "boolean" -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label, style = MaterialTheme.typography.bodyLarge)
                if (f.description.isNotBlank()) Description(f.description)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = value == true, onCheckedChange = { onChange(path, it) }, enabled = canEdit)
        }

        "dict" -> {
            val m = value as? Map<String, Any?> ?: emptyMap()
            val children = f.attrs.filter { visible(it, m) }
            if (children.isEmpty()) return
            val body: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    children.forEach { a ->
                        FieldEditor(a, m[a.variable], m, path + a.variable, onChange, errors, showErrors, enabled, depth + 1)
                    }
                }
            }
            if (depth == 0 && f.label.isNotBlank()) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(f.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (f.description.isNotBlank()) Description(f.description)
                    Spacer(Modifier.size(8.dp))
                    body()
                }
            } else {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (f.label.isNotBlank()) Text(f.label, style = MaterialTheme.typography.titleSmall)
                        if (f.description.isNotBlank()) Description(f.description)
                        Spacer(Modifier.size(8.dp))
                        body()
                    }
                }
            }
        }

        "list" -> {
            val list = value as? List<Any?> ?: emptyList()
            val item = f.items.firstOrNull()
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (f.description.isNotBlank()) Description(f.description)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.size(4.dp))
                list.forEachIndexed { i, v ->
                    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${item?.label?.ifBlank { null } ?: f.label} ${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onChange(path, list.toMutableList().also { it.removeAt(i) }) }, enabled = canEdit) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        }
                        if (item != null) {
                            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                                FieldEditor(
                                    item, v, (v as? Map<String, Any?>) ?: emptyMap(), path + i,
                                    onChange, errors, showErrors, enabled, depth + 1,
                                )
                            }
                        }
                    }
                }
                if (item != null) {
                    TextButton(onClick = { onChange(path, list + listOf(defaultValue(item))) }, enabled = canEdit) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add")
                    }
                }
            }
        }

        else -> {
            if (f.enum.isNotEmpty()) {
                EnumField(f, label, value, error, canEdit) { onChange(path, it) }
            } else if (f.type == "int" || f.type == "float") {
                NumberField(f, label, value, error, canEdit) { onChange(path, it) }
            } else {
                TextValueField(f, label, value, error, canEdit) { onChange(path, it) }
            }
        }
    }
}

@Composable
private fun Description(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TextValueField(f: Field, label: String, value: Any?, error: String?, enabled: Boolean, onChange: (Any?) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    val isPath = f.type == "hostpath" || f.type == "path"
    val text = value?.toString() ?: ""
    OutlinedTextField(
        value = text,
        onValueChange = { onChange(if (it.isEmpty() && f.nullable) null else it) },
        label = { Text(label) },
        singleLine = f.type != "text",
        minLines = if (f.type == "text") 3 else 1,
        enabled = enabled,
        isError = error != null,
        supportingText = (error ?: f.description.ifBlank { null })?.let { { Text(it) } },
        visualTransformation = if (f.private && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (f.private) KeyboardType.Password else KeyboardType.Text),
        trailingIcon = when {
            f.private -> { { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (reveal) "Hide" else "Show") } } }
            f.type == "hostpath" -> { { IconButton(onClick = { browsing = true }, enabled = enabled) { Icon(Icons.Filled.FolderOpen, contentDescription = "Browse") } } }
            else -> null
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (browsing && isPath) {
        FolderPicker(start = text, onDismiss = { browsing = false }, onPick = { onChange(it); browsing = false })
    }
}

@Composable
private fun NumberField(f: Field, label: String, value: Any?, error: String?, enabled: Boolean, onChange: (Any?) -> Unit) {
    var text by remember { mutableStateOf(value?.let { if (it is Double) fmt(it) else it.toString() } ?: "") }
    // Keep in step when the value changes from outside (e.g. a list item above was removed).
    LaunchedEffect(value) {
        if (value !is String) {
            val parsed: Any? = if (f.type == "int") text.toLongOrNull() else text.toDoubleOrNull()
            if (!looseEquals(parsed, value)) text = value?.let { if (it is Double) fmt(it) else it.toString() } ?: ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.trim()
            onChange(
                when {
                    text.isEmpty() -> null
                    f.type == "int" -> text.toLongOrNull() ?: text
                    else -> text.toDoubleOrNull() ?: text
                }
            )
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = (error ?: listOfNotNull(
            f.description.ifBlank { null },
            if (f.min != null && f.max != null) "${fmt(f.min)}–${fmt(f.max)}" else null,
        ).joinToString(" · ").ifBlank { null })?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (f.type == "int") KeyboardType.Number else KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EnumField(f: Field, label: String, value: Any?, error: String?, enabled: Boolean, onChange: (Any?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = f.enum.firstOrNull { looseEquals(it.first, value) }?.second ?: value?.toString().orEmpty()
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            enabled = enabled,
            isError = error != null,
            supportingText = (error ?: f.description.ifBlank { null })?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            f.enum.forEach { (v, desc) ->
                DropdownMenuItem(text = { Text(desc) }, onClick = { onChange(v); open = false })
            }
        }
    }
}

/** Pick a folder on the NAS (for "host path" storage), starting under /mnt. */
@Composable
private fun FolderPicker(start: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf(if (start.startsWith("/mnt/")) start.trimEnd('/') else "/mnt") }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        entries = null
        error = null
        try {
            entries = Graph.api.listDir(path).filter { it.isDir }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The saved path may not exist any more; fall back to the top.
            if (path != "/mnt") path = "/mnt" else error = friendlyError(e)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column {
                Text(path, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (path != "/mnt") {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { path = path.substringBeforeLast('/').ifBlank { "/mnt" } }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text("Up one level")
                            }
                        }
                    }
                    val list = entries
                    when {
                        error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                        list == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
                        list.isEmpty() -> item { Text("No folders here", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp)) }
                        else -> items(list, key = { it.path }) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable { path = e.path }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = TrueNasBlue)
                                Spacer(Modifier.width(12.dp))
                                Text(e.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }, enabled = path != "/mnt") { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
