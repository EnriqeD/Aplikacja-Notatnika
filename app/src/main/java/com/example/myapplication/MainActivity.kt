package com.example.myapplication

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// ==========================================
// 1. BAZA DANYCH
// ==========================================

@Entity(tableName = "users")
data class User(
    @PrimaryKey val username: String,
    val password: String, // Zwykły tekst (brak SHA-256)
    val theme: String = "system"
)

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val ownerUsername: String,
    val color: String = "white"
)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String, // Zwykły tekst (brak AES)
    val folderId: Int? = null,
    val isLocked: Boolean = false, // Tylko flaga logiczna
    val ownerUsername: String,
    val isFavorite: Boolean = false
)

@Dao
interface AppDao {
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getUser(username: String): User?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: User)
    @Query("UPDATE users SET password = :newPassword WHERE username = :username")
    suspend fun updateUserPassword(username: String, newPassword: String)
    @Query("UPDATE users SET theme = :theme WHERE username = :username")
    suspend fun updateUserTheme(username: String, theme: String)
    @Query("DELETE FROM users WHERE username = :username")
    suspend fun deleteUser(username: String)

    @Query("DELETE FROM notes WHERE ownerUsername = :username")
    suspend fun deleteAllUserNotes(username: String)
    @Query("DELETE FROM folders WHERE ownerUsername = :username")
    suspend fun deleteAllUserFolders(username: String)

    // --- NOTATKI ---
    @Query("SELECT * FROM notes WHERE ownerUsername = :username ORDER BY id DESC")
    fun getAllNotes(username: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId AND ownerUsername = :username ORDER BY id DESC")
    fun getNotesByFolder(folderId: Int, username: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isFavorite = 1 AND ownerUsername = :username ORDER BY id DESC")
    fun getFavoriteNotes(username: String): Flow<List<Note>>

    @Insert
    suspend fun insertNote(note: Note)
    @Delete
    suspend fun deleteNote(note: Note)

    @Query("UPDATE notes SET title = :title, content = :content WHERE id = :id")
    suspend fun updateNoteContent(id: Int, title: String, content: String)
    @Query("UPDATE notes SET folderId = :folderId WHERE id = :noteId")
    suspend fun updateNoteFolder(noteId: Int, folderId: Int?)

    // ZMIANA: Tylko flaga isLocked, bez zmiany contentu (bo nie szyfrujemy)
    @Query("UPDATE notes SET isLocked = :isLocked WHERE id = :noteId")
    suspend fun updateNoteLock(noteId: Int, isLocked: Boolean)

    @Query("UPDATE notes SET isFavorite = :isFavorite WHERE id = :noteId")
    suspend fun updateNoteFavorite(noteId: Int, isFavorite: Boolean)

    // --- FOLDERY ---
    @Query("SELECT * FROM folders WHERE ownerUsername = :username ORDER BY id DESC")
    fun getAllFolders(username: String): Flow<List<Folder>>
    @Insert
    suspend fun insertFolder(folder: Folder)
    @Delete
    suspend fun deleteFolder(folder: Folder)
    @Query("UPDATE folders SET name = :name, color = :color WHERE id = :id")
    suspend fun updateFolder(id: Int, name: String, color: String)
}

// Wersja 14 - Powrót do braku szyfrowania (reset bazy)
@Database(entities = [Note::class, Folder::class, User::class], version = 14, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notes_app_v14")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

// ==========================================
// 2. VIEWMODEL
// ==========================================

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).dao()
    var currentUser by mutableStateOf<User?>(null)
        private set
    var currentLuxValue by mutableFloatStateOf(0f)
    val FAVORITES_FOLDER_ID = -1

    // --- REJESTRACJA I LOGOWANIE (BEZ SZYFROWANIA) ---

    fun registerUser(user: User, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        try {
            if (dao.getUser(user.username) != null) onError("Użytkownik już istnieje!")
            else {
                dao.insertUser(user) // Zapisujemy hasło wprost
                onSuccess()
            }
        } catch (e: Exception) { onError("Błąd: ${e.message}") }
    }

    fun loginUser(username: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        val user = dao.getUser(username)
        // Porównujemy hasło tekstowe
        if (user != null && user.password == password) {
            currentUser = user
            onSuccess()
        } else onError("Błędny login lub hasło!")
    }

    fun logout() { currentUser = null }

    fun changePassword(newPasswordPlain: String, onSuccess: () -> Unit) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.updateUserPassword(user.username, newPasswordPlain) // Zapisujemy nowe hasło wprost
            currentUser = user.copy(password = newPasswordPlain)
            onSuccess()
        }
    }

    fun changeTheme(newTheme: String) = viewModelScope.launch {
        currentUser?.let { user -> dao.updateUserTheme(user.username, newTheme); currentUser = user.copy(theme = newTheme) }
    }
    fun deleteAccount(onSuccess: () -> Unit) = viewModelScope.launch {
        currentUser?.let { user -> dao.deleteAllUserNotes(user.username); dao.deleteAllUserFolders(user.username); dao.deleteUser(user.username); currentUser = null; onSuccess() }
    }

    // --- POBIERANIE ---
    fun getAllNotesForCurrentUser() = currentUser?.let { dao.getAllNotes(it.username) } ?: emptyFlow()
    fun getAllFoldersForCurrentUser() = currentUser?.let { dao.getAllFolders(it.username) } ?: emptyFlow()
    fun getNotesByContext(folderId: Int?): Flow<List<Note>> {
        val user = currentUser ?: return emptyFlow()
        return if (folderId == FAVORITES_FOLDER_ID) dao.getFavoriteNotes(user.username)
        else if (folderId != null) dao.getNotesByFolder(folderId, user.username)
        else dao.getAllNotes(user.username)
    }

    // --- OPERACJE NA NOTATKACH ---

    fun addNote(title: String, content: String, folderId: Int?) = viewModelScope.launch {
        currentUser?.let { user ->
            val realFolderId = if(folderId == FAVORITES_FOLDER_ID) null else folderId
            val isFav = (folderId == FAVORITES_FOLDER_ID)
            dao.insertNote(Note(title = title, content = content, folderId = realFolderId, ownerUsername = user.username, isFavorite = isFav))
        }
    }

    fun updateNote(id: Int, t: String, c: String) = viewModelScope.launch { dao.updateNoteContent(id, t, c) }
    fun deleteNote(note: Note) = viewModelScope.launch { dao.deleteNote(note) }
    fun moveNote(note: Note, fId: Int?) = viewModelScope.launch { dao.updateNoteFolder(note.id, fId) }

    // ZMIANA: Tylko przełączanie flagi, brak szyfrowania/deszyfrowania
    fun toggleLock(note: Note) = viewModelScope.launch {
        dao.updateNoteLock(note.id, !note.isLocked)
    }

    fun toggleFavorite(note: Note) = viewModelScope.launch { dao.updateNoteFavorite(note.id, !note.isFavorite) }
    fun addFolder(name: String, color: String) = viewModelScope.launch { currentUser?.let { dao.insertFolder(Folder(name = name, ownerUsername = it.username, color = color)) } }
    fun updateFolder(id: Int, name: String, color: String) = viewModelScope.launch { dao.updateFolder(id, name, color) }
    fun deleteFolder(folder: Folder) = viewModelScope.launch { dao.deleteFolder(folder) }
}

// ==========================================
// 3. UI Helpers
// ==========================================

fun getFolderColor(colorName: String): Color {
    return when(colorName) {
        "black" -> Color.Black
        "blue" -> Color(0xFF2196F3)
        "green" -> Color(0xFF4CAF50)
        "yellow" -> Color(0xFFFFEB3B)
        "orange" -> Color(0xFFFF9800)
        "gold" -> Color(0xFFFFD700)
        else -> Color.White
    }
}
fun getContentColorForFolder(colorName: String): Color {
    return when(colorName) { "black", "blue" -> Color.White; else -> Color.Black }
}
@Composable
fun ColorPicker(selectedColor: String, onColorSelected: (String) -> Unit) {
    val colors = listOf("white", "black", "blue", "green", "yellow", "orange")
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        colors.forEach { colorName ->
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(getFolderColor(colorName)).border(width = if (selectedColor == colorName) 3.dp else 1.dp, color = if (selectedColor == colorName) Color.Gray else Color.LightGray, shape = CircleShape).clickable { onColorSelected(colorName) })
        }
    }
}

// ==========================================
// 4. EKRANY
// ==========================================

@Composable
fun LoginScreen(viewModel: MainViewModel, onLoginSuccess: () -> Unit, onNavigateToRegister: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(80.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp)); Text("Zaloguj się", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground); Spacer(Modifier.height(32.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Login") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Hasło") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        Button({ if (username.isNotBlank() && password.isNotBlank()) viewModel.loginUser(username, password, onLoginSuccess, { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }) else Toast.makeText(context, "Uzupełnij dane", Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth().height(50.dp)) { Text("Wejdź") }
        Spacer(Modifier.height(16.dp)); TextButton(onNavigateToRegister) { Text("Nie masz konta? Zarejestruj się") }
    }
}

@Composable
fun RegisterScreen(viewModel: MainViewModel, onRegisterSuccess: () -> Unit, onNavigateToLogin: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.PersonAdd, null, Modifier.size(80.dp), MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(24.dp)); Text("Załóż konto", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground); Spacer(Modifier.height(32.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Login") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Hasło") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(confirm, { confirm = it }, label = { Text("Potwierdź") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        Button({
            if (username.isBlank() || password.isBlank()) Toast.makeText(context, "Błąd", Toast.LENGTH_SHORT).show()
            else if (password != confirm) Toast.makeText(context, "Hasła różne", Toast.LENGTH_SHORT).show()
            else viewModel.registerUser(User(username, password), { Toast.makeText(context, "Sukces", Toast.LENGTH_SHORT).show(); onRegisterSuccess() }, { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() })
        }, Modifier.fillMaxWidth().height(50.dp)) { Text("Zarejestruj się") }
        Spacer(Modifier.height(16.dp)); TextButton(onNavigateToLogin) { Text("Logowanie") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: MainViewModel, onLogout: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeFolderId by remember { mutableStateOf<Int?>(null) }
    var activeFolderName by remember { mutableStateOf("") }

    BackHandler(enabled = activeFolderId != null) { activeFolderId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activeFolderId != null) Text(activeFolderName)
                    else when(selectedTab) { 0 -> Text("NOTES"); 1 -> Text("Foldery"); else -> Text("Ustawienia") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                navigationIcon = { if (activeFolderId != null) IconButton({ activeFolderId = null }) { Icon(Icons.Default.ArrowBack, "Wróć") } },
                actions = { if (selectedTab != 2) TextButton(onLogout) { Text("Wyloguj") } }
            )
        },
        bottomBar = {
            if (activeFolderId == null) {
                NavigationBar {
                    NavigationBarItem(icon = { Icon(Icons.Default.Description, null) }, label = { Text("Notatki") }, selected = selectedTab == 0, onClick = { selectedTab = 0 })
                    NavigationBarItem(icon = { Icon(Icons.Default.Folder, null) }, label = { Text("Foldery") }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
                    NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ustawienia") }, selected = selectedTab == 2, onClick = { selectedTab = 2 })
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            key(viewModel.currentUser) {
                if (activeFolderId != null) NotesView(viewModel, activeFolderId)
                else when(selectedTab) {
                    0 -> NotesView(viewModel, null)
                    1 -> FoldersView(viewModel, { folderId, name -> activeFolderId = folderId; activeFolderName = name })
                    2 -> SettingsView(viewModel, onLogout)
                }
            }
        }
    }
}

@Composable
fun SettingsView(viewModel: MainViewModel, onLogout: () -> Unit) {
    val context = LocalContext.current
    var showPwdDialog by remember { mutableStateOf(false) }
    var showDelDialog by remember { mutableStateOf(false) }
    var newPwd by remember { mutableStateOf("") }
    val currentTheme = viewModel.currentUser?.theme ?: "system"
    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.AccountCircle, null, Modifier.size(100.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Użytkownik: ${viewModel.currentUser?.username}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp)); Divider(); Spacer(Modifier.height(16.dp))
        Text("Motyw aplikacji", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val themes = listOf("system" to "Systemowy", "light" to "Jasny", "dark" to "Ciemny", "sensor" to "Automatyczny (Czujnik)")
        Column(Modifier.fillMaxWidth()) {
            themes.forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().height(48.dp).selectable(selected = (currentTheme == key), onClick = { viewModel.changeTheme(key) }, role = Role.RadioButton).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = (currentTheme == key), onClick = null)
                    Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (currentTheme == "sensor") {
                Text("Aktualne światło: ${viewModel.currentLuxValue} lux (Próg: 20.0 lux)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            }
        }
        Spacer(Modifier.height(24.dp)); Divider(); Spacer(Modifier.height(24.dp))
        OutlinedButton({ showPwdDialog = true }, Modifier.fillMaxWidth().height(50.dp)) { Text("Zmień hasło") }
        Spacer(Modifier.height(16.dp))
        Button({ showDelDialog = true }, Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Usuń konto") }
        if (showPwdDialog) { AlertDialog(onDismissRequest = { showPwdDialog = false }, title = { Text("Zmiana hasła") }, text = { OutlinedTextField(newPwd, { newPwd = it }, label = { Text("Nowe hasło") }) }, confirmButton = { Button({ if(newPwd.isNotBlank()) viewModel.changePassword(newPwd) { showPwdDialog = false; newPwd="" } }) { Text("Zapisz") } }, dismissButton = { TextButton({ showPwdDialog = false }) { Text("Anuluj") } }) }
        if (showDelDialog) { AlertDialog(onDismissRequest = { showDelDialog = false }, title = { Text("Usunąć konto?") }, text = { Text("Nieodwracalnie usunie wszystkie notatki.") }, confirmButton = { Button({ viewModel.deleteAccount { showDelDialog = false; onLogout() } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Usuń") } }, dismissButton = { TextButton({ showDelDialog = false }) { Text("Anuluj") } }) }
    }
}

@Composable
fun NotesView(viewModel: MainViewModel, folderId: Int?) {
    val notes by viewModel.getNotesByContext(folderId).collectAsState(initial = emptyList())
    val allFolders by viewModel.getAllFoldersForCurrentUser().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }
    val dialogScroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        if(notes.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Brak notatek", color = Color.Gray) } }
        LazyColumn(Modifier.fillMaxSize()) {
            items(notes) { note ->
                val folderName = if (note.folderId != null) allFolders.find { it.id == note.folderId }?.name ?: "Ogólne" else "Ogólne"
                NoteItem(note, folderName, viewModel)
            }
        }
        FloatingActionButton({ showAdd = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.Add, null) }

        if (showAdd) {
            AlertDialog(
                onDismissRequest = { showAdd = false },
                title = { Text("Nowa notatka") },
                text = { Column(Modifier.verticalScroll(dialogScroll)) { OutlinedTextField(newTitle, { newTitle = it }, label = { Text("Tytuł") }); Spacer(Modifier.height(8.dp)); OutlinedTextField(newContent, { newContent = it }, label = { Text("Treść") }, modifier = Modifier.heightIn(min = 100.dp, max = 300.dp)) } },
                confirmButton = { Button({ viewModel.addNote(if(newTitle.isBlank()) "Bez tytułu" else newTitle, newContent, folderId); newTitle=""; newContent=""; showAdd=false }) { Text("Zapisz") } },
                dismissButton = { TextButton({ showAdd = false }) { Text("Anuluj") } }
            )
        }
    }
}

@Composable
fun NoteItem(note: Note, folderName: String, viewModel: MainViewModel) {
    val context = LocalContext.current
    var isMenuExpanded by remember { mutableStateOf(false) }
    var toUnlock by remember { mutableStateOf(false) }
    var toEdit by remember { mutableStateOf(false) }
    var toMove by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf("") }
    var editTitle by remember { mutableStateOf(note.title) }
    var editContent by remember { mutableStateOf(note.content) }
    val allFolders by viewModel.getAllFoldersForCurrentUser().collectAsState(initial = emptyList())

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp), colors = if(note.isLocked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else CardDefaults.cardColors(), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (note.isLocked) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Treść ukryta", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp)
                        }
                    } else { Text(note.content, style = MaterialTheme.typography.bodyMedium, maxLines = 10) }
                    Spacer(Modifier.height(8.dp))
                    Text(folderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleFavorite(note) }) {
                        Icon(imageVector = if(note.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, contentDescription = "Ulubione", tint = if(note.isFavorite) Color(0xFFFFD700) else MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { isMenuExpanded = !isMenuExpanded }) { Icon(imageVector = if (isMenuExpanded) Icons.Default.ExpandLess else Icons.Default.MoreVert, contentDescription = "Opcje") }
                }
            }
            AnimatedVisibility(visible = isMenuExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { if (note.isLocked) toUnlock = true else viewModel.toggleLock(note) }) { Icon(imageVector = if (note.isLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = "Szyfruj", tint = if (note.isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                        if (!note.isLocked) {
                            IconButton(onClick = { editTitle = note.title; editContent = note.content; toEdit = true }) { Icon(Icons.Default.Edit, "Edytuj", tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { toMove = true }) { Icon(Icons.Default.DriveFileMove, "Przenieś", tint = MaterialTheme.colorScheme.secondary) }
                        }
                        IconButton(onClick = { viewModel.deleteNote(note) }) { Icon(Icons.Default.Delete, "Usuń", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if(toUnlock) {
        AlertDialog(onDismissRequest = { toUnlock = false; pwd="" }, title = { Text("Hasło") }, text = { OutlinedTextField(pwd, { pwd = it }, visualTransformation = PasswordVisualTransformation(), label = { Text("Hasło użytkownika") }) },
            confirmButton = { Button({
                // ZMIANA: Sprawdzamy hasło wprost (bez hashowania)
                if(pwd == viewModel.currentUser?.password) { viewModel.toggleLock(note); toUnlock=false; pwd="" }
                else Toast.makeText(context, "Błędne hasło", Toast.LENGTH_SHORT).show()
            }) { Text("OK") } }, dismissButton = { TextButton({ toUnlock=false }) { Text("Anuluj") } })
    }
    if (toEdit) { AlertDialog(onDismissRequest = { toEdit = false }, title = { Text("Edytuj") }, text = { Column { OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Tytuł") }); Spacer(Modifier.height(8.dp)); OutlinedTextField(editContent, { editContent = it }, label = { Text("Treść") }, modifier = Modifier.height(150.dp)) } }, confirmButton = { Button({ viewModel.updateNote(note.id, if(editTitle.isBlank()) "Bez tytułu" else editTitle, editContent); toEdit = false }) { Text("Zapisz") } }, dismissButton = { TextButton({ toEdit = false }) { Text("Anuluj") } }) }
    if (toMove) { AlertDialog(onDismissRequest = { toMove = false }, title = { Text("Przenieś do") }, text = { LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) { item { ListItem(headlineContent = { Text("Brak folderu", fontWeight = FontWeight.Bold) }, modifier = Modifier.clickable { viewModel.moveNote(note, null); toMove = false }); Divider() }; items(allFolders) { folder -> ListItem(headlineContent = { Text(folder.name) }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { viewModel.moveNote(note, folder.id); toMove = false }) } } }, confirmButton = { TextButton({ toMove = false }) { Text("Anuluj") } }) }
}

@Composable
fun FoldersView(viewModel: MainViewModel, onFolderClick: (Int, String) -> Unit) {
    val folders by viewModel.getAllFoldersForCurrentUser().collectAsState(initial = emptyList())
    val allNotes by viewModel.getAllNotesForCurrentUser().collectAsState(initial = emptyList())
    val hasFavorites = allNotes.any { it.isFavorite }

    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("white") }

    Box(Modifier.fillMaxSize()) {
        LazyColumn {
            if (hasFavorites) { item { val favFolder = Folder(id = viewModel.FAVORITES_FOLDER_ID, name = "Ulubione", ownerUsername = "", color = "gold"); FolderItem(favFolder, viewModel, onClick = { onFolderClick(it.id, it.name) }) } }
            items(folders) { f -> FolderItem(f, viewModel, onClick = { onFolderClick(it.id, it.name) }) }
        }
        FloatingActionButton({ showAdd = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.CreateNewFolder, null) }
        if(showAdd) {
            AlertDialog(
                onDismissRequest = { showAdd = false },
                title = { Text("Nowy Folder") },
                text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Nazwa") }); Spacer(Modifier.height(8.dp)); Text("Wybierz kolor:"); ColorPicker(selectedColor = color, onColorSelected = { color = it }) } },
                confirmButton = { Button({ if(name.isNotBlank()) { viewModel.addFolder(name, color); name=""; color="white"; showAdd=false } }) { Text("Utwórz") } },
                dismissButton = { TextButton({ showAdd = false }) { Text("Anuluj") } }
            )
        }
    }
}

@Composable
fun FolderItem(folder: Folder, viewModel: MainViewModel, onClick: (Folder) -> Unit) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    var toEdit by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(folder.name) }
    var editColor by remember { mutableStateOf(folder.color) }

    val backgroundColor = getFolderColor(folder.color)
    val contentColor = getContentColorForFolder(folder.color)
    val isVirtualFavorites = (folder.id == viewModel.FAVORITES_FOLDER_ID)

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onClick(folder) }, colors = CardDefaults.cardColors(containerColor = backgroundColor)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if(isVirtualFavorites) Icons.Default.Star else Icons.Default.Folder, null, tint = contentColor)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(folder.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = contentColor)
                }
                if (!isVirtualFavorites) {
                    IconButton(onClick = { isMenuExpanded = !isMenuExpanded }) { Icon(imageVector = if (isMenuExpanded) Icons.Default.ExpandLess else Icons.Default.MoreVert, contentDescription = "Opcje", tint = contentColor) }
                }
            }
            if (!isVirtualFavorites) {
                AnimatedVisibility(visible = isMenuExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = contentColor.copy(alpha = 0.5f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = { editName = folder.name; editColor = folder.color; toEdit = true }) { Icon(Icons.Default.Edit, "Edytuj", tint = contentColor) }
                            IconButton(onClick = { viewModel.deleteFolder(folder) }) { Icon(Icons.Default.Delete, "Usuń", tint = if (folder.color == "white") MaterialTheme.colorScheme.error else contentColor) }
                        }
                    }
                }
            }
        }
    }
    if (toEdit && !isVirtualFavorites) {
        AlertDialog(
            onDismissRequest = { toEdit = false }, title = { Text("Edytuj Folder") },
            text = { Column { OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Nazwa") }); Spacer(Modifier.height(8.dp)); Text("Zmień kolor:"); ColorPicker(selectedColor = editColor, onColorSelected = { editColor = it }) } },
            confirmButton = { Button(onClick = { if (editName.isNotBlank()) { viewModel.updateFolder(folder.id, editName, editColor); toEdit = false } }) { Text("Zapisz") } },
            dismissButton = { TextButton(onClick = { toEdit = false }) { Text("Anuluj") } }
        )
    }
}

enum class ScreenState { LOGIN, REGISTER, APP }

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val userTheme = viewModel.currentUser?.theme ?: "system"
            var rawSensorIsDark by remember { mutableStateOf(false) }
            var finalSensorIsDark by remember { mutableStateOf(false) }
            if (userTheme == "sensor") {
                DisposableEffect(Unit) {
                    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) { event?.let { val lux = it.values[0]; viewModel.currentLuxValue = lux; rawSensorIsDark = lux < 20f } }
                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                    sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    onDispose { sensorManager.unregisterListener(listener) }
                }
                LaunchedEffect(rawSensorIsDark) { delay(3000); finalSensorIsDark = rawSensorIsDark }
            }
            val isDark = when (userTheme) { "light" -> false; "dark" -> true; "sensor" -> finalSensorIsDark; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                var currentScreen by remember { mutableStateOf(ScreenState.LOGIN) }
                AnimatedContent(targetState = currentScreen, label = "AppNav", transitionSpec = { if (targetState == ScreenState.APP) (slideInHorizontally { width -> width } + fadeIn(tween(500))).togetherWith(slideOutHorizontally { width -> -width } + fadeOut(tween(500))) else (slideInHorizontally { width -> -width } + fadeIn(tween(500))).togetherWith(slideOutHorizontally { width -> width } + fadeOut(tween(500))) }) { screen ->
                    when (screen) {
                        ScreenState.LOGIN -> LoginScreen(viewModel, { currentScreen = ScreenState.APP }, { currentScreen = ScreenState.REGISTER })
                        ScreenState.REGISTER -> RegisterScreen(viewModel, { currentScreen = ScreenState.LOGIN }, { currentScreen = ScreenState.LOGIN })
                        ScreenState.APP -> MainAppScreen(viewModel, { viewModel.logout(); currentScreen = ScreenState.LOGIN })
                    }
                }
            }
        }
    }
}