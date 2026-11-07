package art.argan.data

/**
 * The project format's version stamp and compatibility rules.
 *
 * P0 ships only the version contract. The directory-based format itself
 * (manifest + binary blobs, journaling, autosave) is P9, and nothing here
 * presumes its structure yet — but the *forward-compatibility rule* is fixed now
 * because it constrains every later phase:
 *
 * - A reader must open any project whose [FORMAT_VERSION] is less than or equal
 *   to its own, and must refuse (never silently degrade) a newer one.
 * - A writer may only raise [FORMAT_VERSION] when older readers would still
 *   open the file correctly.
 */
public object ProjectFormat {
    /** Bumped only under the rule above. */
    public const val FORMAT_VERSION: Int = 1

    /** Manifest file name inside a project directory. */
    public const val MANIFEST_FILE_NAME: String = "manifest.json"

    public fun isReadableByThisBuild(projectVersion: Int): Boolean =
        projectVersion in 1..FORMAT_VERSION
}
