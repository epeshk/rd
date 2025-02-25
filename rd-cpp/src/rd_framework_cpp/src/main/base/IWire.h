#ifndef RD_CPP_IWIRE_H
#define RD_CPP_IWIRE_H


#include "interfaces.h"
#include "IRdReactive.h"
#include "Property.h"

namespace rd {
	/**
	 * \brief Sends and receives serialized object data over a network or a similar connection.
	 */
	class IWire {
	public:
		Property<bool> connected{false};

		//region ctor/dtor

		IWire() = default;

		IWire(IWire &&) = default;

		virtual ~IWire() = default;
		//endregion

		/**
		 * \brief Sends a data block with the given [id] and the given [writer] function that can write the data.
		 * \param id of recipient.
		 * \param writer is used to serialise data before send. 
		 */
		virtual void send(RdId const &id, std::function<void(Buffer &buffer)> writer) const = 0;

		/**
		 * \brief Adds a [handler] for receiving updated values of the object with the given [id]. The handler is removed
		 * when the given [lifetime] is terminated.
		 * \param lifetime lifetime of subscription.
		 * \param entity to be subscripted
		 */
		virtual void advise(Lifetime lifetime, IRdReactive const *entity) const = 0;
	};
}


#endif //RD_CPP_IWIRE_H
