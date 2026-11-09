package au.opendepth.app.data

import au.opendepth.app.model.DataSource

/** The in-memory offline-data-source list and its persistence, mirroring [WaypointStore]'s shape. */
class DataSourceStore(private val repository: DataSourceRepository) {

    private val items = mutableListOf<DataSource>()

    val all: List<DataSource> get() = items

    fun load() {
        items.clear()
        items.addAll(repository.load())
    }

    private fun save() = repository.save(items)

    fun add(name: String, urlTemplate: String, attribution: String): DataSource {
        val source = DataSource(
            id = DataSourceRepository.newId(),
            name = name,
            urlTemplate = urlTemplate,
            attribution = attribution
        )
        items += source
        save()
        return source
    }

    /** @return false without changing anything if [id] is missing or is the built-in preset. */
    fun remove(id: String): Boolean {
        val target = items.firstOrNull { it.id == id } ?: return false
        if (target.builtIn) return false
        items.remove(target)
        save()
        return true
    }
}
