package com.agdp.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.*

private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
private fun eur(v: Double) = "%.2f€".format(v)
private fun toAmount(s: String) = s.replace(',', '.').toDoubleOrNull()

// ======================= 1. LOGIN =======================
@Composable
fun LoginScreen(vm: AppVM, onLogged: () -> Unit) {
    val logged by vm.loggedIn.collectAsState()
    LaunchedEffect(logged) { if (logged) onLogged() }
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("AGDP", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Text("Gestão de despesas pessoais e familiares")
        Spacer(Modifier.height(24.dp))
        if (register) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Palavra-passe") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (register) vm.register(name, email, pass) else vm.login(email, pass) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (register) "Criar conta" else "Entrar") }
        TextButton(onClick = { register = !register }) {
            Text(if (register) "Já tenho conta" else "Criar conta nova")
        }
    }
}

// ======================= 2. DASHBOARD =======================
@Composable
fun DashboardScreen(vm: AppVM) {
    val family by vm.family.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val month = expenses.filter { it.date >= monthStart() }
    val spent = month.sumOf { it.amount }
    val budget = family?.budget ?: 0.0
    val progress = if (budget > 0) (spent / budget).toFloat() else 0f
    val barColor = when { progress >= 1f -> Color.Red; progress >= 0.8f -> Color(0xFFFF9800); else -> MaterialTheme.colorScheme.primary }
    val perPerson = month.groupBy { it.userName }.mapValues { e -> e.value.sumOf { it.amount } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Olá, ${vm.userName.collectAsState().value}", style = MaterialTheme.typography.headlineSmall)
            if (family == null) Text("Ainda não tens família. Vai ao separador Família para criar ou entrar numa.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Este mês", style = MaterialTheme.typography.labelLarge)
                    Text("Disponível: ${eur(budget - spent)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Gasto ${eur(spent)} de ${eur(budget)}")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = barColor, modifier = Modifier.fillMaxWidth().height(10.dp))
                    Text("%.0f%% do limite".format(progress * 100))
                    if (progress >= 1f) Text("⚠ Limite ultrapassado!", color = Color.Red)
                    else if (progress >= 0.8f) Text("⚠ Quase no limite", color = Color(0xFFFF9800))
                }
            }
        }
        item { Text("Gasto por pessoa", style = MaterialTheme.typography.titleMedium) }
        items(perPerson.entries.sortedByDescending { it.value }) { (name, v) ->
            val pct = if (spent > 0) (v / spent * 100) else 0.0
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name); Text("${eur(v)}  (%.0f%%)".format(pct))
                }
                LinearProgressIndicator(progress = { (pct / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (perPerson.isEmpty()) item { Text("Sem despesas este mês.") }
    }
}

// ======================= 3. ADD RECEIPT =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(vm: AppVM, onSaved: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(CATEGORIES[0]) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) { uri = it; bmp = null } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { if (it != null) { bmp = it; uri = null } }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Novo recibo", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(amount, { amount = it }, label = { Text("Valor (€)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(desc, { desc = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth())
        Text("Categoria")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CATEGORIES) { c: String -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { camera.launch(null) }) { Text("Tirar foto") }
            OutlinedButton(onClick = { gallery.launch("image/*") }) { Text("Galeria") }
        }
        uri?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Fit) }
        bmp?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(200.dp)) }
        Button(
            enabled = !saving, modifier = Modifier.fillMaxWidth(),
            onClick = {
                val v = toAmount(amount)
                if (v == null || v <= 0) { vm.message.value = "Valor inválido"; return@Button }
                saving = true
                vm.addExpense(v, category, desc, uri, bmp) { saving = false; amount = ""; desc = ""; uri = null; bmp = null; onSaved() }
            }
        ) { Text(if (saving) "A guardar..." else "Guardar") }
    }
}


// ======================= 4. HISTORY =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: AppVM) {
    val expenses by vm.expenses.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    val list = expenses.filter { filter == null || it.category == filter }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Histórico", style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Todas") }) }
            items(CATEGORIES) { c: String -> FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(c) }) }
        }
        Text("Total: ${eur(list.sumOf { it.amount })}", Modifier.padding(vertical = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (e.photoUrl.isNotEmpty())
                            AsyncImage(e.photoUrl, null, Modifier.size(64.dp), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f)) {
                            Text("${e.category} • ${e.description}", fontWeight = FontWeight.Bold)
                            Text("${e.userName} — ${fmt.format(Date(e.date))}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(eur(e.amount), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ======================= 5. FAMILY =======================
@Composable
fun FamilyScreen(vm: AppVM, onLogout: () -> Unit) {
    val family by vm.family.collectAsState()
    val members by vm.members.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val loggedIn by vm.loggedIn.collectAsState()
    LaunchedEffect(loggedIn) { if (!loggedIn) onLogout() }

    var famName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var budget by remember(family?.budget) { mutableStateOf((family?.budget ?: 200.0).toString()) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Família", style = MaterialTheme.typography.headlineSmall) }
        if (family == null) {
            item {
                OutlinedTextField(famName, { famName = it }, label = { Text("Nome da nova família") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.createFamily(famName, 200.0) }, enabled = famName.isNotBlank()) { Text("Criar família") }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(code, { code = it }, label = { Text("Código de família") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.joinFamily(code) }, enabled = code.isNotBlank()) { Text("Entrar") }
            }
        } else {
            item {
                Text(family!!.name, style = MaterialTheme.typography.titleLarge)
                Text("Código para convidar: ${family!!.id}")
                OutlinedTextField(budget, { budget = it }, label = { Text("Limite mensal (€)") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Button(onClick = { toAmount(budget)?.let { vm.setBudget(it) } }) { Text("Guardar limite") }
            }
            items(members) { m ->
                val mine = expenses.filter { it.userId == m.uid && it.date >= monthStart() }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(m.name, fontWeight = FontWeight.Bold); Text(eur(mine.sumOf { it.amount }))
                        }
                        CATEGORIES.forEach { c ->
                            val t = mine.filter { it.category == c }.sumOf { it.amount }
                            if (t > 0) Text("• $c: ${eur(t)}", style = MaterialTheme.typography.bodySmall)
                        }
                        mine.take(5).forEach {
                            Text("${it.description.ifBlank { it.category }} — ${eur(it.amount)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { vm.leaveFamily() }) { Text("Sair da família") } }
        }
        item { Button(onClick = { vm.logout() }) { Text("Terminar sessão") } }
    }
}
