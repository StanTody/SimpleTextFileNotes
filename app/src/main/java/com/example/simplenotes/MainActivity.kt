package com.example.simplenotes

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.io.InputStreamReader

data class NoteFile(
    val fileName: String,
    val title: String,
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
    val focusManager = LocalFocusManager.current
    val sharedPreferences = remember {
        context.getSharedPreferences("SimpleNotesPrefs", Context.MODE_PRIVATE)
    }

    var selectedFolderUri by remember {
        mutableStateOf(
            sharedPreferences.getString("saved_folder_uri", null)?.let { Uri.parse(it) }
        )
    }

    var notesList by remember { mutableStateOf<List<NoteFile>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    
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
                val fileName = file.name ?: continue
                val title = fileName.removeSuffix(".txt").replace("_", " ")
                val lastModified = file.lastModified()
                loadedNotes.add(NoteFile(fileName, title, file.uri, lastModified))
            }
        }
        notesList = loadedNotes.sortedByDescending { it.lastModified }
    }

    LaunchedEffect(selectedFolderUri) {
        if (selectedFolderUri != null) {
            loadNotes()
        }
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
            sharedPreferences.edit().putString("saved_folder_uri", uri.toString()).apply()
            loadNotes()
        }
    }

    fun saveNote() {
        val folderUri = selectedFolderUri
        if (folderUri == null) {
            Toast.makeText(context, "Please select a folder from the menu first!", Toast.LENGTH_SHORT).show()
            return
        }
        val content = editorText.trim()
        if (content.isEmpty()) return

        val firstLine = content.lineSequence().firstOrNull()?.trim() ?: "Untitled"
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
            Toast.makeText(context, "Error saving note: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun deleteNote(note: NoteFile) {
        val folderUri = selectedFolderUri ?: return
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
        val file = folder.findFile(note.fileName)
        file?.delete()
        loadNotes()
    }

    val filteredNotes = remember(notesList, appliedQuery) {
        if (appliedQuery.isBlank()) {
            notesList
        } else {
            val query = appliedQuery.trim()
            notesList.filter { note ->
                if (note.title.contains(query, ignoreCase = true)) {
                    true
                } else {
                    try {
                        context.contentResolver.openInputStream(note.uri)?.use { stream ->
                            val text = InputStreamReader(stream).readText()
                            text.contains(query, ignoreCase = true)
                        } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Select Folder") },
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
                    // Използваме стабилен Modifier.fillMaxHeight(0.65f) вместо BoxWithConstraints
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { editorText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.65f),
                        placeholder = { Text("Write your note... First line becomes the filename.") }
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
                            Text("Cancel")
                        }
                        Button(onClick = { saveNote() }) {
                            Text("Save")
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
                            placeholder = { Text("Search...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    appliedQuery = searchQuery
                                    focusManager.clearFocus() // Стабилен начин за скриване на фокуса/клавиатурата
                                }
                            ),
                            leadingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        appliedQuery = ""
                                    }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
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
                            Icon(Icons.Default.Add, contentDescription = "New Note")
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
                    Text("Please select a folder from the top right menu.")
                }
            } else if (filteredNotes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notes found.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredNotes) { note ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = note.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { deleteNote(note) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                                Button(
                                    onClick = {
                                        val content = try {
                                            context.contentResolver.openInputStream(note.uri)?.use { stream ->
                                                InputStreamReader(stream).readText()
                                            } ?: ""
                                        } catch (_: Exception) { "" }

                                        currentEditingNote = note
                                        editorText = content
                                        isEditing = true
                                    },
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Text("Open")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
