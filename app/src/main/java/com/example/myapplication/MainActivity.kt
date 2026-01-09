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
import kotlinx.coroutines.launch

// ==========================================
// 1. BAZA DANYCH
// ==========================================

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String
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
    val isLocked: Boolean = false
)

@Dao
interface AppDao {
    @Query("SELECT * FROM notes ORDER BY id DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY id DESC")
    fun getNotesByFolder(folderId: Int): Flow<List<Note>>

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

    @Query("SELECT * FROM folders ORDER BY id DESC")
    fun getAllFolders(): Flow<List<Folder>>

    @Insert
    suspend fun insertFolder(folder: Folder)

    @Delete
    suspend fun deleteFolder(folder: Folder)
}

@Database(entities = [Note::class, Folder::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "notes_app_v6")
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

    val allNotes: Flow<List<Note>> = dao.getAllNotes()
    val allFolders: Flow<List<Folder>> = dao.getAllFolders()

    fun getNotesFromFolder(folderId: Int): Flow<List<Note>> = dao.getNotesByFolder(folderId)

    fun addNote(title: String, content: String, folderId: Int?) = viewModelScope.launch {
        dao.insertNote(Note(title = title, content = content, folderId = folderId, isLocked = false))
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

    fun addFolder(name: String) = viewModelScope.launch { dao.insertFolder(Folder(name = name)) }
    fun deleteFolder(folder: Folder) = viewModelScope.launch { dao.deleteFolder(folder) }
}
// ==========================================
// 3. EKRAN LOGOWANIA
// ==========================================

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Notatnik PRO", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Login (admin)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Hasło (1234)") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            if (username == "admin" && password == "1234") onLoginSuccess()
            else Toast.makeText(context, "Błąd! Login: admin, Hasło: 1234", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Zaloguj się") }
    }
}

// ==========================================
// 4. EKRAN GŁÓWNY I WIDOKI
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
                    else if (selectedTab == 0) Text("Wszystkie Notatki")
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
                actions = { TextButton(onClick = onLogout) { Text("Wyloguj") } }
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

@Composable
fun NotesView(viewModel: MainViewModel, folderId: Int?) {
    val notes by if (folderId != null) viewModel.getNotesFromFolder(folderId).collectAsState(initial = emptyList())
    else viewModel.allNotes.collectAsState(initial = emptyList())
    val allFolders by viewModel.allFolders.collectAsState(initial = emptyList())
    val context = LocalContext.current

    // --- Stany Dialogów ---
    var showAddDialog by remember { mutableStateOf(false) }
    var noteToEdit by remember { mutableStateOf<Note?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var noteToMove by remember { mutableStateOf<Note?>(null) }
    var noteToUnlock by remember { mutableStateOf<Note?>(null) }
    var passwordInput by remember { mutableStateOf("") }

// Pola dla nowej notatki
    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(notes) { note ->
                // Znajdź nazwę folderu dla tej notatki
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
                        // TREŚĆ (Lewa strona)
                        Column(modifier = Modifier.weight(1f)) {
                            // TYTUŁ
                            Text(
                                text = note.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            // TREŚĆ
                            if (note.isLocked) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Treść ukryta", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }
                            } else {
                                Text(
                                    text = note.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // INFORMACJA O FOLDERZE (NOWOŚĆ)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = folderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }

                        // IKONY (Prawa strona)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 1. Szyfrowanie
                            IconButton(onClick = {
                                if (note.isLocked) noteToUnlock = note
                                else { viewModel.toggleLock(note); Toast.makeText(context, "Zablokowano", Toast.LENGTH_SHORT).show() }
                            }) {
                                Icon(
                                    imageVector = if (note.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Szyfruj",
                                    tint = if (note.isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }
                        // Dostępne tylko jeśli odblokowana:
                            if (!note.isLocked) {
                                // 2. EDYCJA
                                IconButton(onClick = {
                                    noteToEdit = note
                                    editTitle = note.title
                                    editContent = note.content
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edytuj", tint = MaterialTheme.colorScheme.primary)
                                }
                        // 3. Przenoszenie
                                IconButton(onClick = { noteToMove = note }) {
                                    Icon(Icons.Default.DriveFileMove, contentDescription = "Przenieś", tint = MaterialTheme.colorScheme.secondary)
                                }
                            }

                            // 4. Usuwanie
                            IconButton(onClick = { viewModel.deleteNote(note) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Usuń", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Dodaj")
        }
// --- Dialog DODAWANIA ---
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

        // --- Dialog EDYCJI ---
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

        // --- Dialog PRZENOSZENIA ---
        if (noteToMove != null) {
            AlertDialog(
                onDismissRequest = { noteToMove = null },
                title = { Text("Przenieś do folderu") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        item {
                            ListItem(
                                headlineContent = { Text("Brak folderu (Ogólne)", fontWeight = FontWeight.Bold) },
                                modifier = Modifier.clickable { viewModel.moveNote(noteToMove!!, null); noteToMove = null }
                            )
                            Divider()
                        }
                        items(allFolders) { folder ->
                            ListItem(
                                headlineContent = { Text(folder.name) },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                                modifier = Modifier.clickable { viewModel.moveNote(noteToMove!!, folder.id); noteToMove = null }
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { noteToMove = null }) { Text("Anuluj") } }
            )
        }

        // --- Dialog HASŁA ---
        if (noteToUnlock != null) {
            AlertDialog(
                onDismissRequest = { noteToUnlock = null; passwordInput = "" },
                title = { Text("Podaj hasło") },
                text = {
                    Column {
                        Text("Aby odszyfrować treść: \"${noteToUnlock?.title}\"")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            label = { Text("Hasło (1234)") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (passwordInput == "1234") {
                            viewModel.toggleLock(noteToUnlock!!)
                            noteToUnlock = null
                            passwordInput = ""
                            Toast.makeText(context, "Odblokowano!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Błędne hasło!", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Odszyfruj") }
                },
                dismissButton = { TextButton(onClick = { noteToUnlock = null; passwordInput = "" }) { Text("Anuluj") } }
            )
        }
    }
}

@Composable
fun FoldersView(viewModel: MainViewModel, onFolderClick: (Folder) -> Unit) {
    val folders by viewModel.allFolders.collectAsState(initial = emptyList())
    var showDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(folders) { folder ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onFolderClick(folder) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(folder.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        IconButton(onClick = { viewModel.deleteFolder(folder) }) { Icon(Icons.Default.Delete, contentDescription = null) }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Dodaj") }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Nowy Folder") },
                text = { TextField(value = folderName, onValueChange = { folderName = it }) },
                confirmButton = {
                    Button(onClick = { if (folderName.isNotBlank()) { viewModel.addFolder(folderName); folderName = ""; showDialog = false } }) { Text("Utwórz") }
                },
                dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Anuluj") } }
            )
        }
    }
}
// ==========================================
// 5. MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var isLoggedIn by remember { mutableStateOf(false) }
                AnimatedContent(
                    targetState = isLoggedIn, label = "Auth",
                    transitionSpec = {
                        if (targetState) (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(500))).togetherWith(slideOutHorizontally { width -> -width } + fadeOut(animationSpec = tween(500)))
                        else (slideInHorizontally { width -> -width } + fadeIn(animationSpec = tween(500))).togetherWith(slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(500)))
                    }
                ) { targetState ->
                    if (targetState) MainAppScreen(viewModel, onLogout = { isLoggedIn = false })
                    else LoginScreen(onLoginSuccess = { isLoggedIn = true })
                }
            }
        }
    }
}




}