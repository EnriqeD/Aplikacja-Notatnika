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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val password: String,
    val theme: String = "system"
)

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val ownerUsername: String
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
    val content: String,
    val folderId: Int? = null,
    val isLocked: Boolean = false,
    val ownerUsername: String
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

    @Query("SELECT * FROM notes WHERE ownerUsername = :username ORDER BY id DESC")
    fun getAllNotes(username: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId AND ownerUsername = :username ORDER BY id DESC")
    fun getNotesByFolder(folderId: Int, username: String): Flow<List<Note>>

    @Insert
    suspend fun insertNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("UPDATE notes SET title = :title, content = :content WHERE id = :id")
    suspend fun updateNoteContent(id: Int, title: String, content: String)

    @Query("UPDATE notes SET folderId = :folderId WHERE id = :noteId")
    suspend fun updateNoteFolder(noteId: Int, folderId: Int?)

    @Query("UPDATE notes SET isLocked = :isLocked WHERE id = :noteId")
    suspend fun updateNoteLock(noteId: Int, isLocked: Boolean)

    @Query("SELECT * FROM folders WHERE ownerUsername = :username ORDER BY id DESC")
    fun getAllFolders(username: String): Flow<List<Folder>>

    @Insert
    suspend fun insertFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)
}

@Database(entities = [Note::class, Folder::class, User::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notes_app_v10")
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

    fun registerUser(user: User, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        try {
            if (dao.getUser(user.username) != null) onError("Użytkownik już istnieje!")
            else { dao.insertUser(user); onSuccess() }
        } catch (e: Exception) { onError("Błąd: ${e.message}") }
    }

    fun loginUser(username: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        val user = dao.getUser(username)
        if (user != null && user.password == password) {
            currentUser = user
            onSuccess()
        } else onError("Błędny login lub hasło!")
    }

    fun logout() { currentUser = null }

    fun changePassword(newPassword: String, onSuccess: () -> Unit) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.updateUserPassword(user.username, newPassword)
            currentUser = user.copy(password = newPassword)
            onSuccess()
        }
    }

    fun changeTheme(newTheme: String) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.updateUserTheme(user.username, newTheme)
            currentUser = user.copy(theme = newTheme)
        }
    }

    fun deleteAccount(onSuccess: () -> Unit) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.deleteAllUserNotes(user.username)
            dao.deleteAllUserFolders(user.username)
            dao.deleteUser(user.username)
            currentUser = null
            onSuccess()
        }
    }

    fun getAllNotesForCurrentUser() = currentUser?.let { dao.getAllNotes(it.username) } ?: emptyFlow()
    fun getAllFoldersForCurrentUser() = currentUser?.let { dao.getAllFolders(it.username) } ?: emptyFlow()
    fun getNotesFromFolder(folderId: Int) = currentUser?.let { dao.getNotesByFolder(folderId, it.username) } ?: emptyFlow()
    fun addNote(title: String, content: String, folderId: Int?) = viewModelScope.launch { currentUser?.let { dao.insertNote(Note(title = title, content = content, folderId = folderId, ownerUsername = it.username)) } }
    fun updateNote(id: Int, t: String, c: String) = viewModelScope.launch { dao.updateNoteContent(id, t, c) }
    fun deleteNote(note: Note) = viewModelScope.launch { dao.deleteNote(note) }
    fun moveNote(note: Note, fId: Int?) = viewModelScope.launch { dao.updateNoteFolder(note.id, fId) }
    fun toggleLock(note: Note) = viewModelScope.launch { dao.updateNoteLock(note.id, !note.isLocked) }
    fun addFolder(name: String) = viewModelScope.launch { currentUser?.let { dao.insertFolder(Folder(name = name, ownerUsername = it.username)) } }
    fun deleteFolder(folder: Folder) = viewModelScope.launch { dao.deleteFolder(folder) }
}

// ==========================================
// 3. UI
// ==========================================

@Composable
fun LoginScreen(viewModel: MainViewModel, onLoginSuccess: () -> Unit, onNavigateToRegister: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(80.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp)); Text("Zaloguj się", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(32.dp))
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

    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.PersonAdd, null, Modifier.size(80.dp), MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(24.dp)); Text("Załóż konto", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(32.dp))
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
                    if (activeFolderId != null) Text("Folder: $activeFolderName")
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
                    1 -> FoldersView(viewModel, { activeFolderId = it.id; activeFolderName = it.name })
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

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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

        if (showPwdDialog) {
            AlertDialog(onDismissRequest = { showPwdDialog = false }, title = { Text("Zmiana hasła") }, text = { OutlinedTextField(newPwd, { newPwd = it }, label = { Text("Nowe hasło") }) }, confirmButton = { Button({ if(newPwd.isNotBlank()) viewModel.changePassword(newPwd) { showPwdDialog = false; newPwd="" } }) { Text("Zapisz") } }, dismissButton = { TextButton({ showPwdDialog = false }) { Text("Anuluj") } })
        }
        if (showDelDialog) {
            AlertDialog(onDismissRequest = { showDelDialog = false }, title = { Text("Usunąć konto?") }, text = { Text("Nieodwracalnie usunie wszystkie notatki.") }, confirmButton = { Button({ viewModel.deleteAccount { showDelDialog = false; onLogout() } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Usuń") } }, dismissButton = { TextButton({ showDelDialog = false }) { Text("Anuluj") } })
        }
    }
}

@Composable
fun NotesView(viewModel: MainViewModel, folderId: Int?) {
    val notes by if (folderId != null) viewModel.getNotesFromFolder(folderId).collectAsState(initial = emptyList()) else viewModel.getAllNotesForCurrentUser().collectAsState(initial = emptyList())
    val allFolders by viewModel.getAllFoldersForCurrentUser().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(notes) { note ->
                val folderName = allFolders.find { it.id == note.folderId }?.name ?: "Ogólne"
                NoteItem(note, folderName, viewModel)
            }
        }
        FloatingActionButton({ showAdd = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.Add, null) }
        if (showAdd) {
            AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Nowa notatka") }, text = { Column { OutlinedTextField(newTitle, { newTitle = it }, label = { Text("Tytuł") }); OutlinedTextField(newContent, { newContent = it }, label = { Text("Treść") }, modifier = Modifier.height(100.dp)) } }, confirmButton = { Button({ viewModel.addNote(if(newTitle.isBlank()) "Bez tytułu" else newTitle, newContent, folderId); newTitle=""; newContent=""; showAdd=false }) { Text("Zapisz") } }, dismissButton = { TextButton({ showAdd = false }) { Text("Anuluj") } })
        }
    }
}

@Composable
fun NoteItem(note: Note, folderName: String, viewModel: MainViewModel) {
    val context = LocalContext.current
    var toUnlock by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth().padding(8.dp), colors = if(note.isLocked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else CardDefaults.cardColors()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(note.title, fontWeight = FontWeight.Bold)
                if(note.isLocked) Text("Treść ukryta", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.secondary) else Text(note.content, maxLines = 2)
                Text(folderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Column {
                IconButton({ if(note.isLocked) toUnlock = true else viewModel.toggleLock(note) }) { Icon(if(note.isLocked) Icons.Default.Lock else Icons.Default.LockOpen, null) }
                if(!note.isLocked) IconButton({ viewModel.deleteNote(note) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if(toUnlock) {
        AlertDialog(onDismissRequest = { toUnlock = false; pwd="" }, title = { Text("Hasło") }, text = { OutlinedTextField(pwd, { pwd = it }, visualTransformation = PasswordVisualTransformation()) }, confirmButton = { Button({ if(pwd == viewModel.currentUser?.password) { viewModel.toggleLock(note); toUnlock=false; pwd="" } else Toast.makeText(context, "Błąd", Toast.LENGTH_SHORT).show() }) { Text("OK") } }, dismissButton = { TextButton({ toUnlock=false }) { Text("Anuluj") } })
    }
}

@Composable
fun FoldersView(viewModel: MainViewModel, onClick: (Folder) -> Unit) {
    val folders by viewModel.getAllFoldersForCurrentUser().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        LazyColumn { items(folders) { f -> Card(Modifier.fillMaxWidth().padding(8.dp).clickable { onClick(f) }) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(f.name, fontWeight = FontWeight.Bold); IconButton({ viewModel.deleteFolder(f) }) { Icon(Icons.Default.Delete, null) } } } } }
        FloatingActionButton({ showAdd = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.CreateNewFolder, null) }
        if(showAdd) {
            AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Nowy Folder") }, text = { OutlinedTextField(name, { name = it }) }, confirmButton = { Button({ if(name.isNotBlank()) { viewModel.addFolder(name); name=""; showAdd=false } }) { Text("OK") } }, dismissButton = { TextButton({ showAdd = false }) { Text("Anuluj") } })
        }
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

            // --- LOGIKA OPÓŹNIENIA (DEBOUNCING) DLA SENSORA ---
            // 1. Surowy odczyt z sensora (zmienia się natychmiast)
            var rawSensorIsDark by remember { mutableStateOf(false) }
            // 2. Stabilny stan używany do zmiany motywu (zmienia się po 3s)
            var finalSensorIsDark by remember { mutableStateOf(false) }

            // Rejestracja sensora
            if (userTheme == "sensor") {
                DisposableEffect(Unit) {
                    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            event?.let {
                                val lux = it.values[0]
                                viewModel.currentLuxValue = lux
                                // Natychmiastowa aktualizacja "surowego" stanu
                                rawSensorIsDark = lux < 20f
                            }
                        }
                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                    sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                    onDispose { sensorManager.unregisterListener(listener) }
                }

                // Debouncing: Czekaj 3 sekundy po zmianie rawSensorIsDark zanim zmienisz finalSensorIsDark
                LaunchedEffect(rawSensorIsDark) {
                    // Jeśli to pierwsza inicjalizacja (np. zaraz po włączeniu opcji), można pominąć delay,
                    // ale dla uproszczenia i stabilności czekamy zawsze.
                    // Funkcja delay zostanie anulowana, jeśli rawSensorIsDark zmieni się ponownie przed upływem czasu.
                    delay(3000)
                    finalSensorIsDark = rawSensorIsDark
                }
            }

            val isDark = when (userTheme) {
                "light" -> false
                "dark" -> true
                "sensor" -> finalSensorIsDark // Używamy stabilnej, opóźnionej wartości
                else -> isSystemInDarkTheme()
            }

            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                var currentScreen by remember { mutableStateOf(ScreenState.LOGIN) }

                AnimatedContent(
                    targetState = currentScreen,
                    label = "AppNav",
                    transitionSpec = {
                        if (targetState == ScreenState.APP) (slideInHorizontally { width -> width } + fadeIn(tween(500))).togetherWith(slideOutHorizontally { width -> -width } + fadeOut(tween(500)))
                        else (slideInHorizontally { width -> -width } + fadeIn(tween(500))).togetherWith(slideOutHorizontally { width -> width } + fadeOut(tween(500)))
                    }
                ) { screen ->
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