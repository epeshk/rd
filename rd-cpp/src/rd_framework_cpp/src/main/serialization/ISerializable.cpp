#include "ISerializable.h"

#include "hash.h"

namespace rd {
	size_t IPolymorphicSerializable::hashCode() const noexcept {
		return rd::hash<void const *>()(static_cast<void const *>(this));
	}

	bool operator==(const IPolymorphicSerializable &lhs, const IPolymorphicSerializable &rhs) {
		return lhs.equals(rhs);
	}

	bool operator!=(const IPolymorphicSerializable &lhs, const IPolymorphicSerializable &rhs) {
		return !(lhs == rhs);
	}
}
