#ifndef RD_CPP_FRAMEWORK_RDID_H
#define RD_CPP_FRAMEWORK_RDID_H

#include "Buffer.h"
#include "hashing.h"
#include "hash.h"

#include "thirdparty.hpp"

#include <cstdint>
#include <string>

#include <memory>

namespace rd {
	class RdId;

	template<>
	struct hash<RdId> {
		size_t operator()(const RdId &value) const noexcept;
	};

	/**
	 * \brief An identifier of the object that participates in the object graph.
	 */
	class RdId {
	public:
		using hash_t = util::hash_t;
	private:
		friend struct hash<RdId>;

		constexpr static hash_t NULL_ID = 0;

		hash_t hash{NULL_ID};
	public:
		friend bool operator==(RdId const &left, RdId const &right);

		friend bool operator!=(const RdId &lhs, const RdId &rhs);

		//region ctor/dtor
		constexpr RdId() = default;

		constexpr RdId(const RdId &other) = default;

		constexpr RdId &operator=(const RdId &other) = default;

		constexpr RdId(RdId &&other) noexcept = default;

		constexpr RdId &operator=(RdId &&other) noexcept = default;

		explicit constexpr RdId(hash_t hash) : hash(hash) {}
		//endregion

//		static const RdId NULL_ID;

		static constexpr RdId Null() {
			return RdId{NULL_ID};
		}

		static constexpr int32_t MAX_STATIC_ID = 1'000'000;

		static RdId read(Buffer &buffer);

		void write(Buffer &buffer) const;

		constexpr hash_t get_hash() const {
			return hash;
		}

		constexpr bool isNull() const {
			return get_hash() == NULL_ID;
		}

		RdId notNull() {
			RD_ASSERT_MSG(!isNull(), "id is null");
			return *this;
		}

		/*template<size_t N>
		constexpr RdId mix(char const (&tail)[N]) const {
			return RdId(util::getPlatformIndependentHash<N>(tail, static_cast<util::constexpr_hash_t>(hash)));
		}*/

		constexpr RdId mix(string_view tail) const {
			return RdId(util::getPlatformIndependentHash(tail, static_cast<util::constexpr_hash_t>(hash)));
		}

		/*constexpr RdId mix(int32_t tail) const {
			return RdId(util::getPlatformIndependentHash(tail, static_cast<util::constexpr_hash_t>(hash)));
		}

		*/
		constexpr RdId mix(int64_t tail) const {
			return RdId(util::getPlatformIndependentHash(tail, static_cast<util::constexpr_hash_t>(hash)));
		}

		friend std::string to_string(RdId const& id);
	};

	inline size_t hash<RdId>::operator()(const RdId &value) const noexcept {
		return hash<RdId::hash_t>()(value.hash);
	}
}



#endif //RD_CPP_FRAMEWORK_RDID_H
