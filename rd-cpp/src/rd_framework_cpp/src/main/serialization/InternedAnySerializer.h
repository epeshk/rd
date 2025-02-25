#ifndef RD_CPP_ANYSERIALIZER_H
#define RD_CPP_ANYSERIALIZER_H

#include "SerializationCtx.h"
#include "RdAny.h"

#include "thirdparty.hpp"

namespace rd {
	//region predeclared

	class Buffer;
	//endregion

	class InternedAnySerializer {
	public:
		static optional<InternedAny> read(SerializationCtx  &ctx, Buffer &buffer) {
			return ctx.get_serializers().readAny(ctx, buffer);
		}

		template<typename T>
		static void write(SerializationCtx  &ctx, Buffer &buffer, T const &value) {
			ctx.get_serializers().writePolymorphicNullable(ctx, buffer, value);
		}
	};
}

#endif //RD_CPP_ANYSERIALIZER_H
