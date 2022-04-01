/**
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */
package com.thizzer.git

import java.lang.ref.WeakReference
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class Git {
    class User(val name: String, val email: String)

    class Repository {
        private val branches = mutableListOf<Branch>()

        fun branch(name: String, configure: Branch.() -> Unit): Branch {
            val branch = Branch(name)
            branch.configure()
            branches.add(branch)
            return branch
        }

        fun getHead(): Branch? {
            return branches.firstOrNull()
        }

        fun getBranchWithName(name: String): Branch? {
            return branches.find { it.name == name }
        }

        fun getCommitWithTag(tag: String): Commit? {
            return branches.flatMap { it.commits }.find { it.hasTag(tag) }
        }

        fun refs(): Map<String, String> {
            val refs = mutableMapOf<String, String>()

            branches.forEach { branch ->
                refs.putAll(branch.commits.associate { "refs/heads/${branch.name}" to it.hash() })

                branch.commits.forEach { commit ->
                    refs.putAll(commit.tags.associate { tag ->
                        if (tag.annotated()) {
                            "refs/tags/${tag.tag}" to tag.hash()
                        } else {
                            "refs/tags/${tag.tag}" to commit.hash()
                        }
                    })
                }
            }

            return refs
        }

        /**
         * Find object inside this repository by hash.
         *
         * @param hash
         *      The hash of the object to find.
         *
         * @return The found object, or null if it could not be found.
         */
        fun byHash(hash: String): Object? {
            branches.forEach { branch ->
                branch.commits.forEach { commit ->
                    if (commit.hash() == hash) {
                        return commit
                    }

                    val subObject = commit.byHash(hash)
                    if (subObject != null) {
                        return subObject
                    }
                }
            }

            return null
        }

        /**
         * Find objects inside this repository by hash.
         *
         * @param hash
         *      The hash of the object to find.
         *
         * @return The found objects, or an empty list if none could not be found.
         */
        fun byHashes(hashes: List<String>): List<Object> {
            val objects = mutableListOf<Object>()
            hashes.distinct().forEach { hash ->
                val obj = byHash(hash)
                if (obj != null) {
                    objects.add(obj)
                }
            }
            return objects
        }

        /**
         * Find objects inside this repository by hash.
         *
         * @param hash
         *      The hash of the object to find.
         *
         * @return The found objects, or an empty list if none could not be found.
         */
        fun byHashesWithChildren(hashes: List<String>, exclude: List<String> = listOf(), depth: Int? = null): List<Object> {
            val parents = byHashes(hashes)

            val objects = mutableListOf<Object>()
            parents.forEach { parent ->
                if (parent is Tree) {
                    if (!parent.hasChildren()) {
                        return@forEach
                    }

                    objects.add(parent)
                    objects.addAll(parent.children(true, true, *exclude.toTypedArray()))
                } else if (parent is Commit) {
                    objects.addAll(parent.all(true, *exclude.toTypedArray(), depth = depth))
                } else if (parent is Tag) {
                    parent.commit.get()?.apply {
                        objects.addAll(all(true, *exclude.toTypedArray(), depth = depth))
                    }
                } else {
                    objects.add(parent)
                }
            }

            return objects.distinctBy { it.hash() }
        }
    }

    sealed class Object(val type: Type) {
        enum class Type(val nr: Int, val prefix: String) {
            BAD(-1, "bad"),

            NONE(0, "none"),

            COMMIT(1, "commit"),

            TREE(2, "tree"),

            BLOB(3, "blob"),

            TAG(4, "tag"),

            /** 5 for future expansion */

            OFS_DELTA(6, "ofs-delta"),

            REF_DELTA(7, "ref-delta"),

            REF_ANY(8, "any"),

            REF_MAX(9, "max")
        }

        private var objectContent: ByteArray = byteArrayOf()
        private var objectContentGenerated: LocalDateTime? = null
        private var objectContentChanged: LocalDateTime = LocalDateTime.now()

        private var objectLock = ReentrantReadWriteLock()

        protected fun <T> read(action: () -> T): T {
            return objectLock.read(action)
        }

        protected fun <T> write(action: () -> T): T {
            val result = objectLock.write(action)
            changed()
            return result
        }

        protected fun changed() {
            objectContentChanged = LocalDateTime.now()
        }

        protected open fun hasChanged(): Boolean {
            return (objectContentGenerated == null || objectContentChanged.isAfter(objectContentGenerated))
        }

        protected open fun keepInMemory(): Boolean {
            return true
        }

        /**
         * Get the object content. For creating object files see `Object.toObject` instead.
         */
        abstract fun toObjectContent(): ByteArray

        /**
         * Get the object content including the object file header.
         *
         * [Git-Internals-Git-Objects](https://git-scm.com/book/en/v2/Git-Internals-Git-Objects)
         */
        fun toObject(): ByteArray {
            return read {
                if (!keepInMemory() || hasChanged()) {
                    objectContent = toObjectContent()
                    objectContentGenerated = LocalDateTime.now()
                }
                val prefix = "${type.prefix} ${objectContent.size}${0.toChar()}"
                prefix.toByteArray() + objectContent
            }
        }

        fun hash(): String {
            return hashBytes().joinToString("") { "%02x".format(it) }
        }

        fun hashBytes(): ByteArray {
            return MessageDigest.getInstance("SHA-1").digest(toObject())
        }
    }

    sealed class NamedObject(type: Type, val name: String) : Object(type)

    open class Tree(name: String) : NamedObject(Type.TREE, name) {
        private val children: MutableList<NamedObject> = mutableListOf()

        fun hasChildren(): Boolean {
            return read {
                children.isNotEmpty()
            }
        }

        fun children(
            distinct: Boolean = false,
            includeChildren: Boolean = false,
            vararg exclude: String
        ): List<Object> {
            return read {
                val all = mutableListOf<Object>()
                children.forEach { child ->
                    if (exclude.contains(child.hash()) || (child is Tree && !child.hasChildren())) {
                        return@forEach
                    }

                    all.add(child)
                    if (includeChildren && child is Tree) {
                        all.addAll(child.children(distinct, includeChildren, *exclude))
                    }
                }
                if (distinct) {
                    return@read all.distinct()
                }
                return@read all
            }
        }

        fun file(path: String, content: File.FileContent, configure: File.() -> Unit = {}) {
            file(path) {
                content(content)
                configure()
            }
        }

        /**
         * Add a file with static content.
         *
         * @param path
         *      Path of the file relative to the repository.
         * @param content
         *      Static content for the file.
         */
        fun file(path: String, content: String, configure: File.() -> Unit = {}) {
            file(path, content.toByteArray(), configure)
        }

        /**
         * Add a file with static content.
         *
         * @param path
         *      Path of the file relative to the repository.
         * @param content
         *      Static content for the file.
         */
        fun file(path: String, content: ByteArray, configure: File.() -> Unit = {}) {
            file(path, File.StaticFileContent(content), configure)
        }

        /**
         * Add a file with dynamic content.
         *
         * @param path
         *      Path of the file relative to the repository.
         * @param content
         *      Async content call to load the content for the file.
         *      The loaded content is not kept anywhere and the call will be made any time the content is needed.
         */
        fun file(path: String, content: () -> ByteArray?, configure: File.() -> Unit = {}) {
            file(path, File.AsyncFileContent(content), configure)
        }

        internal fun file(path: String, configure: File.() -> Unit = {}) {
            write {
                val filePath = Path.of(path)

                var parentFolder: Path? = filePath.parent
                while (parentFolder?.parent != null) {
                    parentFolder = parentFolder.parent
                }

                if (parentFolder == null) {
                    val file = File(filePath.fileName.toString())
                    file.configure()
                    children.add(file)
                    children.sortBy { it.name }
                    return@write
                }

                var parentTree = children.find { it is Folder && it.name == parentFolder.toString() }
                if (parentTree == null) {
                    parentTree = Folder(parentFolder.toString())
                    children.add(parentTree)
                    children.sortBy { it.name }
                }

                (parentTree as Folder).file(filePath.subpath(1, filePath.nameCount).toString(), configure)
            }
        }

        /**
         * @see Object.toObjectContent
         */
        override fun toObjectContent(): ByteArray {
            var fileHashContent = byteArrayOf()

            children.toList().forEach { child ->
                if (child is Tree && !child.hasChildren()) {
                    return@forEach
                }
//                * `100644` or `644`: A normal (not-executable) file.  The majority
//                of files in most projects use this mode.  If in doubt, this is
//                what you want.
//                * `100755` or `755`: A normal, but executable, file.
//                * `120000`: A symlink, the content of the file will be the link target.
//                * `160000`: A gitlink, SHA-1 of the object refers to a commit in
//                another repository. Git links can only be specified by SHA or through
//                a commit mark. They are used to implement submodules.
//                * `040000`: A subdirectory.  Subdirectories can only be specified by
//                SHA or through a tree mark set with `--import-marks`.
                var octal = "100644"
                if (child is Folder) {
                    octal = "40000"
                } else if (child is File) {
                    octal = if (child.symlink) {
                        "120000"
                    } else {
                        "100${child.permissions}"
                    }
                }

                val line = "$octal ${child.name}${0.toChar()}"
                fileHashContent += line.toByteArray() + child.hashBytes()
            }

            return fileHashContent
        }

        /**
         * Find object inside this tree by hash.
         *
         * @param hash
         *      The hash of the object to find.
         *
         * @return The found object, or null if it could not be found.
         */
        fun byHash(hash: String): Object? {
            return read {
                children.forEach { child ->
                    if (child.hash() == hash) {
                        return@read child
                    }

                    if (child is Tree) {
                        val subObject = child.byHash(hash)
                        if (subObject != null) {
                            return@read subObject
                        }
                    }
                }
                return@read null
            }
        }
    }

    class Branch(val name: String) {
        internal val commits = mutableListOf<Commit>()

        /**
         * Add a new commit to this branch.
         *
         * @param message
         *      The message for this commit.
         *
         * @note The parent of the commit will automatically be the most recently added commit. If there are no commits no parent will be specified.
         */
        fun commit(message: String, configure: Commit.() -> Unit): Commit {
            return commit(commits.lastOrNull(), message, configure)
        }

        /**
         * Add a new commit to this branch.
         *
         * @param parent
         *      The parent of this commit.
         * @param message
         *      The message for this commit, or null if it is the first commit.
         */
        fun commit(parent: Commit?, message: String, configure: Commit.() -> Unit): Commit {
            val commit = Commit(parent, message = message)
            commit.configure()
            commits.add(commit)
            return commit
        }

        fun getFirstCommit(): Commit? {
            return commits.firstOrNull()
        }

        fun getLastCommit(): Commit? {
            return commits.lastOrNull()
        }
    }

    class Folder(name: String) : Tree(name)

    class File(fileName: String, private var content: FileContent? = null) : NamedObject(Type.BLOB, fileName) {

        abstract class FileContent {
            abstract fun get(): ByteArray?
        }

        class StaticFileContent(private val content: ByteArray?) : FileContent() {
            override fun get(): ByteArray? {
                return content
            }
        }

        class AsyncFileContent(private val asyncContentCall: () -> ByteArray?) : FileContent() {
            override fun get(): ByteArray? {
                return asyncContentCall()
            }
        }

        companion object {
            const val DEFAULT_FILE_PERMISSIONS: Int = 644
        }

        var permissions: Int = DEFAULT_FILE_PERMISSIONS
            set(value) {
                write {
                    field = value
                }
            }

        var symlink: Boolean = false
            set(value) {
                write {
                    field = value
                }
            }

        /**
         * Set static content for file.
         *
         * @param value
         *      Content to load for the file.
         */
        fun content(value: ByteArray) {
            write {
                content = StaticFileContent(value)
            }
        }

        /**
         * Set content for file.
         *
         * @param value
         *      Content to load for the file.
         *      If the content is AsyncFileContent, the loaded content is not kept anywhere and a call will be made any time the content is needed.
         */
        fun content(value: FileContent) {
            write {
                content = value
            }
        }

        override fun keepInMemory(): Boolean {
            return false
        }

        /**
         * @see Object.toObjectContent
         */
        override fun toObjectContent(): ByteArray {
            return content?.get() ?: byteArrayOf()
        }
    }

    class Commit(parent: Commit? = null, val message: String) : Object(Type.COMMIT) {
        private val tree: Tree = Tree("")
        private val parent: WeakReference<Commit> = WeakReference(parent)
        internal val tags: MutableList<Tag> = mutableListOf()

        var author: User? = null
            set(value) {
                write {
                    field = value
                }
            }
        var date: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
            set(value) {
                write {
                    field = value
                }
            }

        /**
         * Set the author for this commit.
         *
         * @param name
         *      The name of the author.
         * @param email
         *      The email of the author.
         */
        fun author(name: String, email: String) {
            author = User(name, email)
        }

        /**
         * Set the author for this commit.
         *
         * @param user
         *      The user to use as the author of the commit.
         */
        fun author(user: User) {
            author = user
        }

        /**
         * Add a file with static content.
         *
         * @param path
         *      Path of the file relative to the repository.
         * @param content
         *      Static content for the file.
         */
        fun file(path: String, content: String, configure: File.() -> Unit = {}) {
            tree.file(path, content, configure)
        }

        /**
         * Add a file with static content.
         *
         * @param path
         *      Path of the file relative to the repository.
         * @param content
         *      Static content for the file.
         */
        fun file(path: String, content: ByteArray, configure: File.() -> Unit = {}) {
            tree.file(path, content, configure)
        }

        /**
         * Add a file with dynamic content.
         *
         * @param path
         *      Path of the file relative to the repository.
         * @param content
         *      Async content call to load the content for the file.
         *      The loaded content is not kept anywhere and the call will be made any time the content is needed.
         */
        fun file(path: String, content: () -> ByteArray?, configure: File.() -> Unit = {}) {
            tree.file(path, content, configure)
        }

        fun file(path: String, configure: File.() -> Unit = {}) {
            tree.file(path, configure)
        }

        /**
         * Tag the commit.
         *
         * @param tag
         *      The value of the tag.
         */
        fun tag(tag: String) {
            write {
                val simpleTag = Tag(this, tag)
                tags.add(simpleTag)
            }
        }

        /**
         * Tag the commit using an annotated tag.
         *
         * @param tag
         *      The value of the tag.
         * @param message
         *      The content of the tag.
         *
         * @note When the tagger is not configured in the `configure` call, the author of the commit will be used.
         */
        fun tag(tag: String, message: String, configure: Tag.() -> Unit = {}) {
            write {
                val annotatedTag = Tag(this, tag, message)
                annotatedTag.tagger = author
                annotatedTag.configure()
                tags.add(annotatedTag)
            }
        }

        fun all(
            includeSelf: Boolean = true,
            vararg exclude: String,
            depth: Int? = null,
            includeTags: Boolean = true
        ): List<Object> {
            val unfiltered = mutableListOf<Object>()
            if (exclude.contains(hash()) || depth == 0) {
                return unfiltered
            }

            read {
                if (includeSelf) {
                    unfiltered.add(this)
                }
                if (includeTags) {
                    tags.forEach {
                        if (it.annotated() && !exclude.contains(it.hash())) {
                            unfiltered.add(it)
                        }
                    }
                }
                if (tree.hasChildren() && !exclude.contains(tree.hash())) {
                    unfiltered.add(tree)
                    unfiltered.addAll(tree.children(true, true, *exclude))
                }

                val parentDepth = depth?.minus(1)
                parent.get()?.apply {
                    unfiltered.addAll(all(true, *exclude, depth = parentDepth))
                }
            }

            return unfiltered
        }

        /**
         * Find object inside this commit by hash.
         *
         * @param hash
         *      The hash of the object to find.
         *
         * @return The found object, or null if it could not be found.
         */
        fun byHash(hash: String): Object? {
            return read {
                if (tree.hash() == hash) {
                    return@read tree
                }

                tags.find { it.annotated() && it.hash() == hash } ?: tree.byHash(hash)
            }
        }

        fun hasTag(tag: String): Boolean {
            return tags.find { it.tag == tag } != null
        }

        fun hasParent(): Boolean {
            return parent.get() != null
        }

        fun getParent(): Commit? {
            return parent.get()
        }

        /**
         * @see Object.toObjectContent
         */
        override fun toObjectContent(): ByteArray {
            if (author == null) {
                throw RuntimeException("Unable to create object for Commit. Error: Author not configured.")
            }

            val commitDate = date.truncatedTo(TimeUnit.SECONDS.toChronoUnit()).toString()

            var commitContent = "${tree.type.prefix} ${tree.hash()}\n"
            if (parent.get() != null) {
                commitContent += "parent ${parent.get()!!.hash()}\n"
            }
            commitContent += "author ${author?.name ?: "Unknown"} <${author?.email ?: "unknown"}> $commitDate\n"
            commitContent += "committer ${author?.name ?: "Unknown"} <${author?.email ?: "unknown"}> $commitDate\n\n"
            commitContent += message

            return commitContent.toByteArray()
        }
    }

    class Tag(commit: Commit, val tag: String, val message: String? = null) : Object(Type.TAG) {
        internal val commit: WeakReference<Commit> = WeakReference(commit)

        var tagger: User? = null
            set(value) {
                write {
                    field = value
                }
            }

        var date: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
            set(value) {
                write {
                    field = value
                }
            }

        internal fun annotated(): Boolean {
            return message != null
        }

        /**
         * @see Object.toObjectContent
         */
        override fun toObjectContent(): ByteArray {
            if (message == null) {
                throw RuntimeException("Unable to create object for Tag. Error: Trying to objectify non-annotated tag.")
            }

            if (tagger == null) {
                throw RuntimeException("Unable to create object for Tag. Error: Tagger not configured.")
            }

            val tagDate = date.truncatedTo(TimeUnit.SECONDS.toChronoUnit()).toString()

            var tagContent = "object ${commit.get()?.hash()}\n"
            tagContent += "type ${commit.get()?.type?.prefix}\n"
            tagContent += "tag $tag\n"
            tagContent += "tagger ${tagger?.name ?: "Unknown"} <${tagger?.email ?: "unknown"}> $tagDate\n\n"
            tagContent += message

            return tagContent.toByteArray()
        }
    }
}

fun gitRepository(configure: Git.Repository.() -> Unit): Git.Repository {
    val repo = Git.Repository()
    repo.configure()
    return repo
}