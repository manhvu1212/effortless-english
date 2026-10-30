package io.github.manhvu1212.effortless.data.content

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File

/** A file or folder of the course content, whether picked through SAF or downloaded into app storage. */
interface ContentNode {
    val name: String
    val isDirectory: Boolean
    val uri: String
    val children: List<ContentNode>

    fun child(name: String): ContentNode? = children.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Resolves a '/'-separated path relative to this folder. */
    fun resolve(path: String): ContentNode? =
        path.split('/').filter { it.isNotEmpty() }.fold(this as ContentNode?) { node, part -> node?.child(part) }
}

/** One query per folder through DocumentsContract: far faster than DocumentFile, which queries once per attribute. */
class TreeNode private constructor(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    override val name: String,
    override val isDirectory: Boolean,
) : ContentNode {
    override val uri: String
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()

    override val children: List<ContentNode> by lazy {
        if (!isDirectory) return@lazy emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        buildList {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        TreeNode(
                            resolver,
                            treeUri,
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2) == Document.MIME_TYPE_DIR,
                        )
                    )
                }
            }
        }
    }

    companion object {
        fun root(resolver: ContentResolver, treeUri: Uri, name: String = "root"): TreeNode =
            TreeNode(resolver, treeUri, DocumentsContract.getTreeDocumentId(treeUri), name, true)
    }
}

class FileNode(private val file: File) : ContentNode {
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory
    override val uri: String get() = Uri.fromFile(file).toString()
    override val children: List<ContentNode> by lazy {
        file.listFiles()?.map { FileNode(it) }.orEmpty()
    }
}
