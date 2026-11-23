package com.agdp.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.UUID

val CATEGORIES = listOf("Casa", "Comida", "Jogos", "Transporte", "Combustível")

data class Expense(
    val id: String = "", val userId: String = "", val userName: String = "",
    val amount: Double = 0.0, val category: String = "", val description: String = "",
    val date: Long = 0L, val photoUrl: String = ""
)
data class Family(val id: String = "", val name: String = "", val budget: Double = 200.0)
data class Member(val uid: String, val name: String)

fun monthStart(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

class AppVM(app: Application) : AndroidViewModel(app) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val ctx: Context get() = getApplication()

    val loggedIn = MutableStateFlow(auth.currentUser != null)
    val userName = MutableStateFlow("")
    val family = MutableStateFlow<Family?>(null)
    val members = MutableStateFlow<List<Member>>(emptyList())
    val expenses = MutableStateFlow<List<Expense>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    private val uid get() = auth.currentUser?.uid
    private var userReg: ListenerRegistration? = null
    private val famRegs = mutableListOf<ListenerRegistration>()

    init { if (loggedIn.value) listen() }

    // ---------- Auth ----------
    fun login(email: String, pass: String) = run {
        auth.signInWithEmailAndPassword(email.trim(), pass).await()
        loggedIn.value = true; listen()
    }

    fun register(name: String, email: String, pass: String) = run {
        val r = auth.createUserWithEmailAndPassword(email.trim(), pass).await()
        db.collection("users").document(r.user!!.uid).set(mapOf("name" to name.trim())).await()
        loggedIn.value = true; listen()
    }

    fun logout() {
        userReg?.remove(); famRegs.forEach { it.remove() }; famRegs.clear()
        auth.signOut(); loggedIn.value = false
        family.value = null; members.value = emptyList(); expenses.value = emptyList()
    }

    // ---------- Realtime listeners ----------
    private fun listen() {
        val id = uid ?: return
        userReg?.remove()
        userReg = db.collection("users").document(id).addSnapshotListener { s, _ ->
            userName.value = s?.getString("name") ?: ""
            listenFamily(s?.getString("familyId"))
        }
    }

    private fun listenFamily(fid: String?) {
        famRegs.forEach { it.remove() }; famRegs.clear()
        if (fid == null) { family.value = null; members.value = emptyList(); expenses.value = emptyList(); return }
        val fdoc = db.collection("families").document(fid)
        famRegs += fdoc.addSnapshotListener { s, _ ->
            family.value = s?.toObject(Family::class.java)?.copy(id = fid); checkBudget()
        }
        famRegs += db.collection("users").whereEqualTo("familyId", fid).addSnapshotListener { s, _ ->
            members.value = s?.documents?.map { Member(it.id, it.getString("name") ?: "?") } ?: emptyList()
        }
        famRegs += fdoc.collection("expenses").orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { s, _ ->
                expenses.value = s?.documents?.mapNotNull { d ->
                    d.toObject(Expense::class.java)?.copy(id = d.id)
                } ?: emptyList()
                checkBudget()
            }
    }

    // ---------- Family ----------
    fun createFamily(name: String, budget: Double) = run {
        val ref = db.collection("families").document()
        ref.set(mapOf("name" to name, "budget" to budget, "ownerId" to uid)).await()
        db.collection("users").document(uid!!).update("familyId", ref.id).await()
    }

    fun joinFamily(code: String) = run {
        val snap = db.collection("families").document(code.trim()).get().await()
        if (!snap.exists()) error("Código de família inválido")
        db.collection("users").document(uid!!).update("familyId", snap.id).await()
    }

    fun leaveFamily() = run {
        db.collection("users").document(uid!!).update("familyId", FieldValue.delete()).await()
    }

    fun setBudget(budget: Double) = run {
        db.collection("families").document(family.value!!.id).update("budget", budget).await()
    }

    // ---------- Expenses ----------
    fun addExpense(amount: Double, category: String, desc: String, uri: Uri?, bmp: Bitmap?, done: () -> Unit) = run {
        val fid = family.value?.id ?: error("Entra ou cria uma família primeiro")
        var url = ""
        val ref = storage.reference.child("receipts/$fid/${UUID.randomUUID()}.jpg")
        when {
            uri != null -> { ref.putFile(uri).await(); url = ref.downloadUrl.await().toString() }
            bmp != null -> {
                val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                ref.putBytes(out.toByteArray()).await(); url = ref.downloadUrl.await().toString()
            }
        }
        val e = Expense("", uid!!, userName.value, amount, category, desc, System.currentTimeMillis(), url)
        db.collection("families").document(fid).collection("expenses").add(e).await()
        done()
    }

    // ---------- Budget notifications ----------
    // Level 1 = >= 80% of budget, level 2 = >= 100%. Notifies once per level per month, on every device.
    private fun checkBudget() {
        val f = family.value ?: return
        if (f.budget <= 0) return
        val spent = expenses.value.filter { it.date >= monthStart() }.sumOf { it.amount }
        val level = when { spent >= f.budget -> 2; spent >= f.budget * 0.8 -> 1; else -> 0 }
        val prefs = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val key = "${f.id}_${monthStart()}"
        if (level > prefs.getInt(key, 0)) {
            prefs.edit().putInt(key, level).apply()
            if (level == 2) notify(ctx, "Orçamento ultrapassado!", "Gastaram %.2f€ de %.2f€ este mês.".format(spent, f.budget))
            else notify(ctx, "Quase no limite", "Já gastaram %.2f€ de %.2f€ (%.0f%%).".format(spent, f.budget, spent / f.budget * 100))
        }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) { message.value = e.message }
        }
    }
}
