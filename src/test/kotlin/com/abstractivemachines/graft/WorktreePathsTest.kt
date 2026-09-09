package com.abstractivemachines.graft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class WorktreePathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val home: Path get() = temp.root.toPath()

    @Test
    fun `resolve expands tilde against home`() {
        assertEquals(home.resolve("src/repo"), WorktreePaths.resolve("~/src/repo", home))
        assertEquals(home, WorktreePaths.resolve("~", home))
    }

    @Test
    fun `resolve normalizes dot segments`() {
        assertEquals(home.resolve("src/repo"), WorktreePaths.resolve("${home}/src/./x/../repo", home))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolve rejects relative paths`() {
        WorktreePaths.resolve("src/repo", home)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolve rejects blank input`() {
        WorktreePaths.resolve("   ", home)
    }

    @Test
    fun `linkedWorktree reads gitdir pointer and branch`() {
        val main = home.resolve("repo").createDirectories()
        val gitDir = main.resolve(".git/worktrees/SW-1-slug").createDirectories()
        gitDir.resolve("HEAD").writeText("ref: refs/heads/SW-1-slug\n")
        val worktree = home.resolve(".worktrees/repo/SW-1-slug").createDirectories()
        worktree.resolve(".git").writeText("gitdir: $gitDir\n")

        val info = WorktreePaths.linkedWorktree(worktree)
        assertNotNull(info)
        assertEquals(gitDir, info!!.gitDir)
        assertEquals("SW-1-slug", info.branch)
        assertEquals(main, info.mainRepository)
    }

    @Test
    fun `linkedWorktree reports detached head as null branch`() {
        val gitDir = home.resolve("repo/.git/worktrees/wt").createDirectories()
        gitDir.resolve("HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
        val worktree = home.resolve("wt").createDirectories()
        worktree.resolve(".git").writeText("gitdir: $gitDir\n")

        val info = WorktreePaths.linkedWorktree(worktree)
        assertNotNull(info)
        assertNull(info!!.branch)
    }

    @Test
    fun `linkedWorktree is null for a main checkout and for a plain directory`() {
        val main = home.resolve("repo").createDirectories()
        main.resolve(".git").createDirectories()
        assertNull(WorktreePaths.linkedWorktree(main))

        val plain = home.resolve("plain").createDirectories()
        assertNull(WorktreePaths.linkedWorktree(plain))
    }

    @Test
    fun `attachBlocker is null when there is no idea folder`() {
        val dir = home.resolve("wt").createDirectories()
        assertNull(WorktreePaths.attachBlocker(dir, setOf("device")))
    }

    @Test
    fun `attachBlocker is null when the module file does not collide`() {
        val dir = home.resolve("SW-2-slug").createDirectories()
        dir.resolve(".idea").createDirectories().resolve("SW-2-slug.iml").writeText("<module/>")
        assertNull(WorktreePaths.attachBlocker(dir, setOf("device", "SW-1-slug")))
    }

    @Test
    fun `attachBlocker explains a copied idea folder`() {
        val dir = home.resolve("SW-2-slug").createDirectories()
        dir.resolve(".idea").createDirectories().resolve("device.iml").writeText("<module/>")
        val reason = WorktreePaths.attachBlocker(dir, setOf("device"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("Module name already exists"))
        assertTrue(reason.contains("device"))
    }

    @Test
    fun `attachBlocker explains an idea folder without a module file`() {
        val dir = home.resolve("wt").createDirectories()
        dir.resolve(".idea").createDirectories().resolve("workspace.xml").writeText("<project/>")
        val reason = WorktreePaths.attachBlocker(dir, emptySet())
        assertNotNull(reason)
        assertTrue(reason!!.contains("no module (.iml) file"))
    }

    @Test
    fun `deleteIdeaDirectory removes the folder recursively and reports absence`() {
        val dir = home.resolve("wt").createDirectories()
        val idea = dir.resolve(".idea").createDirectories()
        idea.resolve("nested").createDirectories().resolve("a.xml").writeText("x")
        idea.resolve("wt.iml").writeText("<module/>")

        assertTrue(WorktreePaths.deleteIdeaDirectory(dir))
        assertFalse(idea.exists())
        assertTrue(Files.isDirectory(dir))
        assertFalse(WorktreePaths.deleteIdeaDirectory(dir))
    }

    @Test
    fun `samePath treats equivalent paths as equal`() {
        val dir = home.resolve("a/b").createDirectories()
        assertTrue(WorktreePaths.samePath(dir, home.resolve("a/./b/")))
        assertFalse(WorktreePaths.samePath(dir, home.resolve("a")))
    }
}
