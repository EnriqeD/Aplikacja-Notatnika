package com.example.myapplication // <--- ZACHOWAJ SWOJĄ NAZWĘ PAKIETU!

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// ==========================================
// 1. BAZA DANYCH (Model Danych)
// ==========================================

@Entity(tableName = "users")
data class User(
    @PrimaryKey val username: String,
    val password: String
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
    // --- UŻYTKOWNICY ---
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getUser(username: String): User?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: User)

    // NOWOŚĆ: Zmiana hasła
    @Query("UPDATE users SET password = :newPassword WHERE username = :username")
    suspend fun updateUserPassword(username: String, newPassword: String)

    // NOWOŚĆ: Usuwanie konta i danych
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

    // --- FOLDERY ---
    @Query("SELECT * FROM folders WHERE ownerUsername = :username ORDER BY id DESC")
    fun getAllFolders(username: String): Flow<List<Folder>>

    @Insert
    suspend fun insertFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)
}

// Bump wersji do 9 (dodano nowe query, struktura tabel bez zmian, ale dla pewności migracji)
@Database(entities = [Note::class, Folder::class, User::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notes_app_v9")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

// ==========================================
// 2. VIEWMODEL (Logika Biznesowa)
// ==========================================

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).dao()

    var currentUser by mutableStateOf<User?>(null)
        private set

    // --- LOGOWANIE, REJESTRACJA, USTAWIENIA ---

    fun registerUser(user: User, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        try {
            val existing = dao.getUser(user.username)
            if (existing != null) {
                onError("Użytkownik o takim loginie już istnieje!")
            } else {
                dao.insertUser(user)
                onSuccess()
            }
        } catch (e: Exception) {
            onError("Błąd rejestracji: ${e.message}")
        }
    }

    fun loginUser(username: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        val user = dao.getUser(username)
        if (user != null && user.password == password) {
            currentUser = user
            onSuccess()
        } else {
            onError("Błędny login lub hasło!")
        }
    }

    fun logout() {
        currentUser = null
    }

    // NOWOŚĆ: Zmiana hasła
    fun changePassword(newPassword: String, onSuccess: () -> Unit) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.updateUserPassword(user.username, newPassword)
            currentUser = user.copy(password = newPassword) // Aktualizuj lokalny stan
            onSuccess()
        }
    }

    // NOWOŚĆ: Usuwanie konta
    fun deleteAccount(onSuccess: () -> Unit) = viewModelScope.launch {
        currentUser?.let { user ->
            // Usuwamy wszystko co związane z użytkownikiem
            dao.deleteAllUserNotes(user.username)
            dao.deleteAllUserFolders(user.username)
            dao.deleteUser(user.username)
            currentUser = null
            onSuccess()
        }
    }

    // --- NOTATKI I FOLDERY ---

    fun getAllNotesForCurrentUser(): Flow<List<Note>> {
        return currentUser?.let { dao.getAllNotes(it.username) } ?: emptyFlow()
    }

    fun getAllFoldersForCurrentUser(): Flow<List<Folder>> {
        return currentUser?.let { dao.getAllFolders(it.username) } ?: emptyFlow()
    }

    fun getNotesFromFolder(folderId: Int): Flow<List<Note>> {
        return currentUser?.let { dao.getNotesByFolder(folderId, it.username) } ?: emptyFlow()
    }

    fun addNote(title: String, content: String, folderId: Int?) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.insertNote(Note(
                title = title, content = content, folderId = folderId,
                isLocked = false, ownerUsername = user.username
            ))
        }
    }

    fun updateNote(id: Int, title: String, content: String) = viewModelScope.launch {
        dao.updateNoteContent(id, title, content)
    }

    fun deleteNote(note: Note) = viewModelScope.launch { dao.deleteNote(note) }

    fun moveNote(note: Note, folderId: Int?) = viewModelScope.launch {
        dao.updateNoteFolder(note.id, folderId)
    }

    fun toggleLock(note: Note) = viewModelScope.launch {
        dao.updateNoteLock(note.id, !note.isLocked)
    }

    fun addFolder(name: String) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.insertFolder(Folder(name = name, ownerUsername = user.username))
        }
    }

    fun deleteFolder(folder: Folder) = viewModelScope.launch { dao.deleteFolder(folder) }
}

// ==========================================
// 3. WIDOKI LOGOWANIA I REJESTRACJI
// ==========================================

@Composable
fun LoginScreen(viewModel: MainViewModel, onLoginSuccess: () -> Unit, onNavigateToRegister: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Zaloguj się", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Login") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Hasło") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            if (username.isNotBlank() && password.isNotBlank()) {
                viewModel.loginUser(username, password,
                    onSuccess = onLoginSuccess,
                    onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                )
            } else {
                Toast.makeText(context, "Wpisz login i hasło", Toast.LENGTH_SHORT).show()
            }
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Wejdź") }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onNavigateToRegister) { Text("Nie masz konta? Zarejestruj się") }
    }
}

@Composable
fun RegisterScreen(viewModel: MainViewModel, onRegisterSuccess: () -> Unit, onNavigateToLogin: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Załóż konto", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Wybierz Login") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Hasło") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = confirmPassword, onValueChange = { confirmPassword = it }, label = { Text("Potwierdź Hasło") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(context, "Uzupełnij wszystkie pola", Toast.LENGTH_SHORT).show()
            } else if (password != confirmPassword) {
                Toast.makeText(context, "Hasła nie są takie same!", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.registerUser(User(username, password),
                    onSuccess = {
                        Toast.makeText(context, "Konto utworzone! Zaloguj się.", Toast.LENGTH_SHORT).show()
                        onRegisterSuccess()
                    },
                    onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                )
            }
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Zarejestruj się") }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onNavigateToLogin) { Text("Masz już konto? Zaloguj się") }
    }
}

// ==========================================
// 4. GŁÓWNA NAWIGACJA I EKRANY APLIKACJI
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: MainViewModel, onLogout: () -> Unit) {
    // 0 = Notatki, 1 = Foldery, 2 = Ustawienia
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeFolderId by remember { mutableStateOf<Int?>(null) }
    var activeFolderName by remember { mutableStateOf("") }

    BackHandler(enabled = activeFolderId != null) { activeFolderId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activeFolderId != null) Text("Folder: $activeFolderName")
                    else if (selectedTab == 0) Text("NOTES")
                    else if (selectedTab == 1) Text("Moje Foldery")
                    else Text("Ustawienia")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    if (activeFolderId != null) {
                        IconButton(onClick = { activeFolderId = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                        }
                    }
                },
                actions = {
                    if (selectedTab != 2) { // Na ekranie ustawień nie pokazujemy przycisku wyloguj (jest w środku)
                        TextButton(onClick = onLogout) { Text("Wyloguj") }
                    }
                }
            )
        },
        bottomBar = {
            if (activeFolderId == null) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Description, contentDescription = null) },
                        label = { Text("Notatki") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        label = { Text("Foldery") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    // NOWA ZAKŁADKA
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Ustawienia") },
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            key(viewModel.currentUser) {
                if (activeFolderId != null) {
                    NotesView(viewModel, folderId = activeFolderId)
                } else {
                    when (selectedTab) {
                        0 -> NotesView(viewModel, folderId = null)
                        1 -> FoldersView(viewModel, onFolderClick = { folder ->
                            activeFolderId = folder.id
                            activeFolderName = folder.name
                        })
                        2 -> SettingsView(viewModel, onLogout) // NOWY EKRAN
                    }
                }
            }
        }
    }
}

// --- NOWY EKRAN: USTAWIENIA ---
@Composable
fun SettingsView(viewModel: MainViewModel, onLogout: () -> Unit) {
    val context = LocalContext.current
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        Text("Zalogowany jako:", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = viewModel.currentUser?.username ?: "Błąd",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))
        Divider()
        Spacer(modifier = Modifier.height(32.dp))

        // Przycisk zmiany hasła
        OutlinedButton(
            onClick = { showPasswordDialog = true },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.LockReset, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Zmień hasło")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Przycisk usuwania konta
        Button(
            onClick = { showDeleteAccountDialog = true },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Usuń konto")
        }

        // --- Dialog zmiany hasła ---
        if (showPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showPasswordDialog = false; newPassword = "" },
                title = { Text("Zmiana hasła") },
                text = {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Nowe hasło") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newPassword.isNotBlank()) {
                            viewModel.changePassword(newPassword) {
                                Toast.makeText(context, "Hasło zmienione pomyślnie", Toast.LENGTH_SHORT).show()
                                showPasswordDialog = false
                                newPassword = ""
                            }
                        }
                    }) { Text("Zapisz") }
                },
                dismissButton = { TextButton(onClick = { showPasswordDialog = false }) { Text("Anuluj") } }
            )
        }

        // --- Dialog usuwania konta ---
        if (showDeleteAccountDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAccountDialog = false },
                title = { Text("Usunąć konto?") },
                text = { Text("Ta operacja jest nieodwracalna. Wszystkie Twoje notatki i foldery zostaną trwale usunięte.") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteAccount {
                                showDeleteAccountDialog = false
                                onLogout() // Wyloguj i wróć do ekranu logowania
                                Toast.makeText(context, "Konto zostało usunięte", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Tak, usuń") }
                },
                dismissButton = { TextButton(onClick = { showDeleteAccountDialog = false }) { Text("Anuluj") } }
            )
        }
    }
}

// --- WIDOK NOTATEK ---
@Composable
fun NotesView(viewModel: MainViewModel, folderId: Int?) {
    val notes by if (folderId != null) viewModel.getNotesFromFolder(folderId).collectAsState(initial = emptyList())
    else viewModel.getAllNotesForCurrentUser().collectAsState(initial = emptyList())
    val allFolders by viewModel.getAllFoldersForCurrentUser().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val currentUserPassword = viewModel.currentUser?.password ?: ""

    var showAddDialog by remember { mutableStateOf(false) }
    var noteToEdit by remember { mutableStateOf<Note?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var noteToMove by remember { mutableStateOf<Note?>(null) }
    var noteToUnlock by remember { mutableStateOf<Note?>(null) }
    var passwordInput by remember { mutableStateOf("") }

    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Brak notatek", color = MaterialTheme.colorScheme.secondary)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(notes) { note ->
                val folderName = allFolders.find { it.id == note.folderId }?.name ?: "Ogólne"

                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = if(note.isLocked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else CardDefaults.cardColors(),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(4.dp))

                            if (note.isLocked) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Treść ukryta", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }
                            } else {
                                Text(text = note.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = folderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (note.isLocked) noteToUnlock = note
                                else { viewModel.toggleLock(note); Toast.makeText(context, "Zablokowano", Toast.LENGTH_SHORT).show() }
                            }) {
                                Icon(imageVector = if (note.isLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = "Szyfruj", tint = if (note.isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            }

                            if (!note.isLocked) {
                                IconButton(onClick = {
                                    noteToEdit = note
                                    editTitle = note.title
                                    editContent = note.content
                                }) { Icon(Icons.Default.Edit, contentDescription = "Edytuj", tint = MaterialTheme.colorScheme.primary) }

                                IconButton(onClick = { noteToMove = note }) {
                                    Icon(Icons.Default.DriveFileMove, contentDescription = "Przenieś", tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            IconButton(onClick = { viewModel.deleteNote(note) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Usuń", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(onClick = { showAddDialog = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Dodaj")
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Nowa notatka") },
                text = {
                    Column {
                        OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, label = { Text("Tytuł") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = newContent, onValueChange = { newContent = it }, label = { Text("Treść") }, modifier = Modifier.fillMaxWidth().height(150.dp), maxLines = 10)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (newTitle.isNotBlank() || newContent.isNotBlank()) {
                            val finalTitle = if(newTitle.isBlank()) "Bez tytułu" else newTitle
                            viewModel.addNote(finalTitle, newContent, folderId)
                            newTitle = ""; newContent = ""
                            showAddDialog = false
                        }
                    }) { Text("Zapisz") }
                },
                dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Anuluj") } }
            )
        }

        if (noteToEdit != null) {
            AlertDialog(
                onDismissRequest = { noteToEdit = null },
                title = { Text("Edytuj notatkę") },
                text = {
                    Column {
                        OutlinedTextField(value = editTitle, onValueChange = { editTitle = it }, label = { Text("Tytuł") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = editContent, onValueChange = { editContent = it }, label = { Text("Treść") }, modifier = Modifier.fillMaxWidth().height(150.dp), maxLines = 10)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val finalTitle = if(editTitle.isBlank()) "Bez tytułu" else editTitle
                        viewModel.updateNote(noteToEdit!!.id, finalTitle, editContent)
                        noteToEdit = null
                    }) { Text("Aktualizuj") }
                },
                dismissButton = { TextButton(onClick = { noteToEdit = null }) { Text("Anuluj") } }
            )
        }

        if (noteToMove != null) {
            AlertDialog(
                onDismissRequest = { noteToMove = null },
                title = { Text("Przenieś do folderu") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        item {
                            ListItem(headlineContent = { Text("Brak folderu (Ogólne)", fontWeight = FontWeight.Bold) }, modifier = Modifier.clickable { viewModel.moveNote(noteToMove!!, null); noteToMove = null })
                            Divider()
                        }
                        items(allFolders) { folder ->
                            ListItem(headlineContent = { Text(folder.name) }, leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) }, modifier = Modifier.clickable { viewModel.moveNote(noteToMove!!, folder.id); noteToMove = null })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { noteToMove = null }) { Text("Anuluj") } }
            )
        }

        if (noteToUnlock != null) {
            AlertDialog(
                onDismissRequest = { noteToUnlock = null; passwordInput = "" },
                title = { Text("Podaj hasło") },
                text = {
                    Column {
                        Text("Aby odszyfrować treść: \"${noteToUnlock?.title}\"")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = passwordInput, onValueChange = { passwordInput = it }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), label = { Text("Hasło użytkownika") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (passwordInput == currentUserPassword) {
                            viewModel.toggleLock(noteToUnlock!!)
                            noteToUnlock = null; passwordInput = ""; Toast.makeText(context, "Odblokowano!", Toast.LENGTH_SHORT).show()
                        } else { Toast.makeText(context, "Błędne hasło!", Toast.LENGTH_SHORT).show() }
                    }) { Text("Odszyfruj") }
                },
                dismissButton = { TextButton(onClick = { noteToUnlock = null; passwordInput = "" }) { Text("Anuluj") } }
            )
        }
    }
}

// --- WIDOK FOLDERÓW ---
@Composable
fun FoldersView(viewModel: MainViewModel, onFolderClick: (Folder) -> Unit) {
    val folders by viewModel.getAllFoldersForCurrentUser().collectAsState(initial = emptyList())
    var showDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        if (folders.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Brak folderów", color = MaterialTheme.colorScheme.secondary)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(folders) { folder ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onFolderClick(folder) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null); Spacer(modifier = Modifier.width(16.dp))
                            Text(folder.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        IconButton(onClick = { viewModel.deleteFolder(folder) }) { Icon(Icons.Default.Delete, contentDescription = null) }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showDialog = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Dodaj") }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Nowy Folder") },
                text = { TextField(value = folderName, onValueChange = { folderName = it }) },
                confirmButton = { Button(onClick = { if (folderName.isNotBlank()) { viewModel.addFolder(folderName); folderName = ""; showDialog = false } }) { Text("Utwórz") } },
                dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Anuluj") } }
            )
        }
    }
}

enum class ScreenState { LOGIN, REGISTER, APP }

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var currentScreen by remember { mutableStateOf(ScreenState.LOGIN) }

                AnimatedContent(
                    targetState = currentScreen,
                    label = "AppNavigation",
                    transitionSpec = {
                        if (targetState == ScreenState.APP) {
                            (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(500))).togetherWith(slideOutHorizontally { width -> -width } + fadeOut(animationSpec = tween(500)))
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn(animationSpec = tween(500))).togetherWith(slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(500)))
                        }
                    }
                ) { screen ->
                    when (screen) {
                        ScreenState.LOGIN -> LoginScreen(
                            viewModel = viewModel,
                            onLoginSuccess = { currentScreen = ScreenState.APP },
                            onNavigateToRegister = { currentScreen = ScreenState.REGISTER }
                        )
                        ScreenState.REGISTER -> RegisterScreen(
                            viewModel = viewModel,
                            onRegisterSuccess = { currentScreen = ScreenState.LOGIN },
                            onNavigateToLogin = { currentScreen = ScreenState.LOGIN }
                        )
                        ScreenState.APP -> MainAppScreen(
                            viewModel = viewModel,
                            onLogout = {
                                viewModel.logout()
                                currentScreen = ScreenState.LOGIN
                            }
                        )
                    }
                }
            }
        }
    }
}