#ifndef RD_CPP_FRAMEWORK_TRAITS_H
#define RD_CPP_FRAMEWORK_TRAITS_H

// #include "SerializationCtx.h"
// #include "Buffer.h"

#include <utility>
#include <type_traits>

namespace rd {
	namespace util {
		template<typename S, typename T = decltype((S::read(std::declval<rd::SerializationCtx  &>(), std::declval<rd::Buffer &>())))>
		using read_t = T;

		static_assert(util::is_same_v<std::wstring, read_t<Polymorphic<std::wstring>>>, " ");
	}
}

#endif //RD_CPP_FRAMEWORK_TRAITS_H
