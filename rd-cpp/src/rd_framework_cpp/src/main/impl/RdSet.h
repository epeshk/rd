#ifndef RD_CPP_RDSET_H
#define RD_CPP_RDSET_H


#include "ViewableSet.h"
#include "RdReactiveBase.h"
#include "Polymorphic.h"

#pragma warning( push )
#pragma warning( disable:4250 )

namespace rd {
	/**
	 * \brief Reactive set for connection through wire.
	 * 
	 * \tparam T type of stored values 
	 * \tparam S "SerDes" for values
	 */
	template<typename T, typename S = Polymorphic<T>>
	class RdSet final : public RdReactiveBase, public ViewableSet<T>, public ISerializable {
	private:
		using WT = typename IViewableSet<T>::WT;
	protected:
		using set = ViewableSet<T>;
	public:
		using Event = typename IViewableSet<T>::Event;

		using value_type = T;

		//region ctor/dtor

		RdSet() = default;

		RdSet(RdSet &&) = default;

		RdSet &operator=(RdSet &&) = default;

		virtual ~RdSet() = default;

		//endregion

		static RdSet<T, S> read(SerializationCtx  &ctx, Buffer &buffer) {
			RdSet<T, S> result;
			RdId id = RdId::read(buffer);
			withId(result, std::move(id));
			return result;
		}

		void write(SerializationCtx  &ctx, Buffer &buffer) const override {
			rdid.write(buffer);
		}

		bool optimize_nested = false;

		void init(Lifetime lifetime) const override {
			RdBindableBase::init(lifetime);

			local_change([this, lifetime] {
				advise(lifetime, [this](AddRemove kind, T const &v) {
					if (!is_local_change) return;

					get_wire()->send(rdid, [this, kind, &v](Buffer &buffer) {
						buffer.write_enum<AddRemove>(kind);
						S::write(this->get_serialization_context(), buffer, v);

						logSend.trace("SEND"s +
								"set " + to_string(location) + " " + to_string(rdid) +
								":: " + to_string(kind) +
								":: " + to_string(v));
					});
				});
			});

			get_wire()->advise(lifetime, this);
		}

		void on_wire_received(Buffer buffer) const override {
			AddRemove kind = buffer.read_enum<AddRemove>();
			auto value = S::read(this->get_serialization_context(), buffer);

			switch (kind) {
				case AddRemove::ADD : {
					set::add(std::move(value));
					break;
				}
				case AddRemove::REMOVE: {
					set::remove(wrapper::get<T>(value));
					break;
				}
			}
		}

		bool add(WT value) const override {
			return local_change([this, value = std::move(value)]() mutable { return set::add(std::move(value)); });
		}

		void clear() const override {
			return local_change([&] { return set::clear(); });
		}

		bool remove(T const &value) const override {
			return local_change([&] { return set::remove(value); });
		}

		size_t size() const override {
			return local_change([&] { return set::size(); });
		}

		bool contains(T const &value) const override {
			return local_change([&] { return set::contains(value); });
		}

		bool empty() const override {
			return local_change([&] { return set::empty(); });
		}

		void advise(Lifetime lifetime, std::function<void(Event const &)> handler) const override {
			if (is_bound()) {
				assert_threading();
			}
			set::advise(lifetime, std::move(handler));
		}

		bool addAll(std::vector<WT> elements) const override {
			return local_change([this, elements = std::move(elements)]() mutable { return set::addAll(elements); });
		}

		friend std::string to_string(RdSet const &value) {
			std::string res = "[";
			for (auto const& p : value) {
				res += to_string(p) + ",";
			}
			return res + "]";
		}

		using IViewableSet<T>::advise;
	};
}

#pragma warning( pop )

static_assert(std::is_move_constructible<rd::RdSet<int> >::value, "Is move constructible RdSet<int>");

#endif //RD_CPP_RDSET_H
