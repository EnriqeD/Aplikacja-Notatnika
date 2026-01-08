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




}