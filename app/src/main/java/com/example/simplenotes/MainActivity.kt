package com.example.simplenotes

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.io.InputStreamReader

data class NoteFile(
    val fileName: String,
    val title: String,
    val content: String,
    val uri: Uri,
    val lastModified: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotesApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesApp() {
    val context = LocalContext.current
    var selectedFolderUri by remember { mutableStateOf<Uri?>(null) }
    var notesList by remember { mutableStateOf<List<NoteFile>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    
    var currentEditingNote by remember { mutableStateOf<NoteFile?>(null) }
    var editorText by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    fun loadNotes() {
        val folderUri = selectedFolderUri ?: return
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
        val files = folder.listFiles()
        val loadedNotes = mutableListOf<NoteFile>()

        for (file in files) {
            if (file.isFile && file.name?.endsWith(".txt") == true) {
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { stream ->
                        val text = InputStreamReader(stream).readText()
                        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: file.name ?: ""
                        val lastModified = file.lastModified()
                        loadedNotes.add(NoteFile(file.name ?: "", firstLine, text, file.uri, lastModified))
                    }
                } catch (_: Exception) {}
            }
        }
        // Сортираме бележките: най-новите (по време на промяна) отиват най-отгоре
        notesList = loadedNotes.sortedByDescending { it.lastModified }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            selectedFolderUri = uri
            loadNotes()
        }
    }

    fun saveNote() {
        val folderUri = selectedFolderUri
        if (folderUri == null) {
            Toast.makeText(context, "Моля, първо изберете папка от менюто!", Toast.LENGTH_SHORT).show()
            return
        }
        val content = editorText.trim()
        if (content.isEmpty()) return

        val firstLine = content.lineSequence().firstOrNull()?.trim() ?: "Без заглавие"
        val safeName = firstLine.replace(Regex("[/\\\\?%*:|\"<>.]"), "_").take(30) + ".txt"

        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return

        try {
            if (currentEditingNote != null && currentEditingNote!!.fileName != safeName) {
                val oldFile = folder.findFile(currentEditingNote!!.fileName)
                oldFile?.delete()
            }

            var targetFile = folder.findFile(safeName)
            if (targetFile == null) {
                targetFile = folder.createFile("text/plain", safeName)
            }

            targetFile?.uri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                    stream.write(content.toByteArray())
                }
            }

            isEditing = false
            currentEditingNote = null
            editorText = ""
            loadNotes()
        } catch (e: Exception) {
            Toast.makeText(context, "Грешка при запис: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun deleteNote(note: NoteFile) {
        val folderUri = selectedFolderUri ?: return
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
        val file = folder.findFile(note.fileName)
        file?.delete()
        loadNotes()
    }

    val filteredNotes = notesList.filter {
        it.title.contains(searchQuery, ignoreCase = true) ||
                it.content.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Бележки") },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Избери папка") },
                            onClick = {
                                menuExpanded = false
                                folderPicker.launch(null)
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                if (isEditing) {
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { editorText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        placeholder = { Text("Напишете бележка... Първият ред става име на файла.") }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { isEditing = false; currentEditingNote = null },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Отказ")
                        }
                        Button(onClick = { saveNote() }) {
                            Text("Запази")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Търсене...") },
                            singleLine = true,
                            // Добавяме бутон за изчистване от лявата страна (leadingIcon), когато има текст
                            leadingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Изчисти търсенето")
                                    }
                                }
                            } else null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FloatingActionButton(
                            onClick = {
                                currentEditingNote = null
                                editorText = ""
                                isEditing = true
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Нова бележка")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (selectedFolderUri == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Моля, изберете папка от менюто горе вдясно.")
                }
            } else if (filteredNotes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Няма намерени бележки.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredNotes) { note ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp) // Намалени вертикални отстояния наполовина
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp), // По-компактен вътрешен padding
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = note.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                // Разменени бутони: Изтриване отляво, Отваряне отдясно
                                IconButton(onClick = { deleteNote(note) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Изтрий")
                                }
                                Button(
                                    onClick = {
                                        currentEditingNote = note
                                        editorText = note.content
                                        isEditing = true
                                    },
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Text("Отвори")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
