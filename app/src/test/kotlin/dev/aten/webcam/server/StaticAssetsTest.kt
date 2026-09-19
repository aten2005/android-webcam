package dev.aten.webcam.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StaticAssetsTest {
    // Gradle runs unit tests with the module directory as the working directory.
    private val webDirectory = File("src/main/assets/web")
    private val assets = StaticAssets { name -> File(webDirectory, name).takeIf { it.isFile }?.readBytes() }

    @Test
    fun everyRouteServesABundledFile() {
        for (path in StaticAssets.paths) {
            val response = assets.response(assets.route(path)!!)
            assertEquals("route $path", 200, response.status)
            assertTrue("route $path is empty", response.body.isNotEmpty())
        }
    }

    @Test
    fun everyBundledFileHasARoute() {
        val routed = StaticAssets.paths.map { assets.route(it)!!.asset }.toSet()
        assertEquals(webDirectory.list()!!.toSet(), routed)
    }

    @Test
    fun pagesOnlyReferenceRoutedResources() {
        val references = Regex("""(?:src|href)="(/[^"]*)"""")
        for (page in listOf("index.html", "login.html")) {
            val html = File(webDirectory, page).readText()
            for (match in references.findAll(html)) {
                assertTrue("$page references ${match.groupValues[1]}", match.groupValues[1] in StaticAssets.paths)
            }
            assertTrue("$page must not use inline scripts", !Regex("<script(?![^>]*src=)").containsMatchIn(html))
        }
    }

    @Test
    fun unknownPathsHaveNoRoute() {
        assertNull(assets.route("/index.html"))
        assertNull(assets.route("/../AndroidManifest.xml"))
    }

    @Test
    fun onlyLoginResourcesArePublic() {
        val publicPaths = StaticAssets.paths.filter { assets.route(it)!!.public }.toSet()
        assertEquals(setOf("/login", "/login.js", "/style.css"), publicPaths)
    }
}
