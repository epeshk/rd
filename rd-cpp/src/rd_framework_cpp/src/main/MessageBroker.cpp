#include "MessageBroker.h"

#include "core_util.h"
#include "Buffer.h"

#include <cassert>



namespace rd {
	Logger MessageBroker::logger;

	void MessageBroker::invoke(const IRdReactive *that, Buffer msg, bool sync) const {
		if (sync) {
			that->on_wire_received(std::move(msg));
		} else {
			auto action = [this, that, message = std::move(msg)]() mutable {
				bool exists_id = false;
				{
					std::lock_guard<decltype(lock)> guard(lock);
					exists_id = subscriptions.count(that->rdid) > 0;
				}
				if (exists_id) {
					that->on_wire_received(std::move(message));
				} else {
					logger.trace("Disappeared Handler for Reactive entities with id:" + to_string(that->rdid));
				}
			};
			std::function<void()> function = util::make_shared_function(std::move(action));
			that->get_wire_scheduler()->queue(std::move(function));
		}
	}

	MessageBroker::MessageBroker(IScheduler *defaultScheduler) : default_scheduler(defaultScheduler) {}

	void MessageBroker::dispatch(RdId id, Buffer message) const {
		RD_ASSERT_MSG(!id.isNull(), "id mustn't be null")

		{//synchronized recursively
			std::lock_guard<decltype(lock)> guard(lock);
			IRdReactive const *s = subscriptions[id];
			if (s == nullptr) {
				auto it = broker.find(id);
				if (it == broker.end()) {
					it = broker.emplace(id, Mq{}).first;
				}

				broker[id].default_scheduler_messages.emplace(std::move(message));

				auto action = [this, it, id]() mutable {
					auto &current = it->second;
					IRdReactive const *subscription = subscriptions[id];

					optional<Buffer> message;
					{
						std::lock_guard<decltype(lock)> guard(lock);
						if (!current.default_scheduler_messages.empty()) {
							message = make_optional<Buffer>(std::move(current.default_scheduler_messages.front()));
							current.default_scheduler_messages.pop();
						}
					}
					if (subscription != nullptr) {
						if (message) {
							invoke(subscription, *std::move(message), subscription->get_wire_scheduler() == default_scheduler);
						}
					} else {
						logger.trace("No handler for id: " + to_string(id));
					}

					if (current.default_scheduler_messages.empty()) {
						auto t = std::move(broker[id]);
						broker.erase(id);
						for (auto &it : t.custom_scheduler_messages) {
							RD_ASSERT_MSG(subscription->get_wire_scheduler() != default_scheduler, "require equals of wire and default schedulers")
							invoke(subscription, std::move(it));
						}
					}
				};
				std::function<void()> function = util::make_shared_function(std::move(action));
				default_scheduler->queue(std::move(function));
			} else {
				if (s->get_wire_scheduler() == default_scheduler || s->get_wire_scheduler()->out_of_order_execution) {
					invoke(s, std::move(message));
				} else {
					auto it = broker.find(id);
					if (it == broker.end()) {
						invoke(s, std::move(message));
					} else {
						Mq &mq = it->second;
						mq.custom_scheduler_messages.push_back(std::move(message));
					}
				}
			}
		}

		//        }
	}

	void MessageBroker::advise_on(Lifetime lifetime, IRdReactive const *entity) const {
		RD_ASSERT_MSG(!entity->rdid.isNull(), ("id is null for entities: " + std::string(typeid(*entity).name())))

		//advise MUST happen under default scheduler, not custom
		default_scheduler->assert_thread();

		std::lock_guard<decltype(lock)> guard(lock);
		if (!lifetime->is_terminated()) {
			auto key = entity->rdid;
			IRdReactive const *value = entity;
			subscriptions[key] = value;
			lifetime->add_action([this, key]() {
				subscriptions.erase(key);
			});
		}
	}
}

