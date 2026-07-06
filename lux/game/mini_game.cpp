#include "game/mini_game.h"

namespace lux {

MiniGameRegistry& MiniGameRegistry::instance() {
    static MiniGameRegistry registry;
    return registry;
}

void MiniGameRegistry::registerCreator(const std::string& id, MiniGameCreator creator) {
    Entry entry;
    entry.creator = std::move(creator);
    registry_[id] = std::move(entry);
}

MiniGame* MiniGameRegistry::create(const std::string& id) const {
    auto it = registry_.find(id);
    if (it != registry_.end()) {
        return it->second.creator();
    }
    return nullptr;
}

bool MiniGameRegistry::hasGame(const std::string& id) const {
    return registry_.find(id) != registry_.end();
}

std::vector<std::string> MiniGameRegistry::availableGames() const {
    std::vector<std::string> ids;
    ids.reserve(registry_.size());
    for (const auto& [id, _] : registry_) {
        ids.push_back(id);
    }
    return ids;
}

std::vector<MiniGameInfo> MiniGameRegistry::gameInfos() const {
    std::vector<MiniGameInfo> infos;
    infos.reserve(registry_.size());
    for (auto& [id, entry] : registry_) {
        if (!entry.infoInstance) {
            entry.infoInstance.reset(entry.creator());
        }
        infos.push_back(entry.infoInstance->getInfo());
    }
    return infos;
}

MiniGameInfo MiniGameRegistry::getGameInfo(const std::string& id) const {
    auto it = registry_.find(id);
    if (it != registry_.end()) {
        if (!it->second.infoInstance) {
            it->second.infoInstance.reset(it->second.creator());
        }
        return it->second.infoInstance->getInfo();
    }
    return {};
}

} // namespace lux
