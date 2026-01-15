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
    val ownerUsername: String // NOWOŚĆ: Właściciel folderu
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
    val ownerUsername: String // NOWOŚĆ: Właściciel notatki
)

@Dao
interface AppDao {
    // --- UŻYTKOWNICY ---
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getUser(username: String): User?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: User)

    // --- NOTATKI (Filtrowane po użytkowniku) ---
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

    // --- FOLDERY (Filtrowane po użytkowniku) ---
    @Query("SELECT * FROM folders WHERE ownerUsername = :username ORDER BY id DESC")
    fun getAllFolders(username: String): Flow<List<Folder>>

    @Insert
    suspend fun insertFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)
}

// Zmiana wersji na 8 (dodano pola ownerUsername)
@Database(entities = [Note::class, Folder::class, User::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notes_app_v8")
                    .fallbackToDestructiveMigration() // Wyczyszczenie bazy przy zmianie struktury
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

    // Aktualnie zalogowany użytkownik
    var currentUser by mutableStateOf<User?>(null)
        private set

    // --- LOGOWANIE I REJESTRACJA ---
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

    // --- POBIERANIE DANYCH (Tylko dla zalogowanego) ---

    // Zwraca notatki tylko zalogowanego użytkownika
    fun getAllNotesForCurrentUser(): Flow<List<Note>> {
        return currentUser?.let { dao.getAllNotes(it.username) } ?: emptyFlow()
    }

    // Zwraca foldery tylko zalogowanego użytkownika
    fun getAllFoldersForCurrentUser(): Flow<List<Folder>> {
        return currentUser?.let { dao.getAllFolders(it.username) } ?: emptyFlow()
    }

    // Zwraca notatki z folderu (weryfikując właściciela)
    fun getNotesFromFolder(folderId: Int): Flow<List<Note>> {
        return currentUser?.let { dao.getNotesByFolder(folderId, it.username) } ?: emptyFlow()
    }

    // --- OPERACJE (Dodawanie z przypisaniem właściciela) ---

    fun addNote(title: String, content: String, folderId: Int?) = viewModelScope.launch {
        currentUser?.let { user ->
            dao.insertNote(Note(
                title = title,
                content = content,
                folderId = folderId,
                isLocked = false,
                ownerUsername = user.username // Przypisanie do użytkownika
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
            dao.insertFolder(Folder(name = name, ownerUsername = user.username)) // Przypisanie folderu
        }
    }

    fun deleteFolder(folder: Folder) = viewModelScope.launch { dao.deleteFolder(folder) }
}

// ==========================================
// 3. EKRAN LOGOWANIA
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

// ==========================================
// 4. EKRAN REJESTRACJI
// ==========================================

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
// 5. EKRAN GŁÓWNY APLIKACJI
// ==========================================

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
                    else if (selectedTab == 0) Text("NOTES")
                    else Text("Moje Foldery")
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
                    // Wyświetla kto jest zalogowany
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                        Text(text = viewModel.currentUser?.username ?: "", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = onLogout) { Text("Wyloguj") }
                    }
                }
            )
        },
        bottomBar = {
            if (activeFolderId == null) {
                NavigationBar {
                    NavigationBarItem(icon = { Icon(Icons.Default.Description, contentDescription = null) }, label = { Text("Notatki") }, selected = selectedTab == 0, onClick = { selectedTab = 0 })
                    NavigationBarItem(icon = { Icon(Icons.Default.Folder, contentDescription = null) }, label = { Text("Foldery") }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // Przekazujemy klucz "currentUser", aby wymusić odświeżenie przy zmianie usera
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
                    }
                }
            }
        }
    }
}

@Composable
fun NotesView(viewModel: MainViewModel, folderId: Int?) {
    // Pobieramy notatki tylko dla zalogowanego użytkownika
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

@Composable
fun FoldersView(viewModel: MainViewModel, onFolderClick: (Folder) -> Unit) {
    // Pobieramy foldery tylko dla zalogowanego użytkownika
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