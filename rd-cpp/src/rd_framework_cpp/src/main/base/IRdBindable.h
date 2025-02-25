#ifndef RD_CPP_FRAMEWORK_IRDBINDABLE_H
#define RD_CPP_FRAMEWORK_IRDBINDABLE_H

#include "IRdDynamic.h"
#include "Lifetime.h"
#include "RdId.h"


namespace rd {
	class Identities;
}

namespace rd {
	/**
	 * \brief A non-root node in an object graph which can be synchronized with its remote copy over a network or 
	 * a similar connection.
	 */
	class IRdBindable : public IRdDynamic {
	public:
		mutable RdId rdid = RdId::Null();

		mutable IRdDynamic const *parent = nullptr;
		//region ctor/dtor

		IRdBindable() = default;

		IRdBindable(IRdBindable &&other) = default;

		IRdBindable &operator=(IRdBindable &&other) = default;

		virtual ~IRdBindable() = default;
		//endregion

		/**
		 * \brief Inserts the node into the object graph under the given [parent] and assigns the specified [name] to it.
		 * The node will be removed from the graph when the specified [lf] lifetime is terminated.
		 * 
		 * \param lf lifetime of node.
		 * \param parent to whom bind.
		 * \param name specified name of node.
		 */
		virtual void bind(Lifetime lf, IRdDynamic const *parent, string_view name) const = 0;

		/**
		 * \brief Assigns IDs to this node and its child nodes in the graph.
		 * 
		 * \param identities to generate unique identifiers for children. 
		 * \param id which is assigned to this node. 
		 */
		virtual void identify(Identities const &identities, RdId const &id) const = 0;
	};

	template<typename T>
	typename std::enable_if_t<!util::is_base_of_v<IRdBindable, typename std::decay_t<T>>>
	inline identifyPolymorphic(T &&, Identities const &identities, RdId const &id) {}

//template <>
	inline void identifyPolymorphic(const IRdBindable &that, Identities const &identities, RdId const &id) {
		that.identify(identities, id);
	}

	template<typename T>
	typename std::enable_if_t<util::is_base_of_v<IRdBindable, T>>
	inline identifyPolymorphic(std::vector<T> const &that, Identities const &identities, RdId const &id) {
		for (size_t i = 0; i < that.size(); ++i) {
			that[i].identify(identities, id.mix(static_cast<int32_t >(i)));
		}
	}

	template<typename T>
	typename std::enable_if_t<!util::is_base_of_v<IRdBindable, typename std::decay_t<T>>>
	inline bindPolymorphic(T &&, Lifetime lf, const IRdDynamic *parent, string_view name) {}

	inline void
	bindPolymorphic(IRdBindable const &that, Lifetime lf, const IRdDynamic *parent, string_view name) {
		that.bind(lf, parent, name);
	}

	template<typename T>
	typename std::enable_if_t<util::is_base_of_v<IRdBindable, T>>
	inline
	bindPolymorphic(std::vector<T> const &that, Lifetime lf, IRdDynamic const *parent, string_view name) {
		for (auto &obj : that) {
			obj.bind(lf, parent, name);
		}
	}
}

#endif //RD_CPP_FRAMEWORK_IRDBINDABLE_H
