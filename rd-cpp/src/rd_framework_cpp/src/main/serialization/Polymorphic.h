#ifndef RD_CPP_POLYMORPHIC_H
#define RD_CPP_POLYMORPHIC_H

#include "Buffer.h"
#include "RdReactiveBase.h"

#include <type_traits>


namespace rd {
	//region predeclared

	class SerializationCtx;
	//endregion

	/**
	 * \brief Maintains "SerDes" for statically polymorphic type [T].
	 * Requires static "read" and "write" methods as in common case below.
	 * \tparam T type to "SerDes"
	 * \tparam R trait specialisation (void by default)
	 */
	template<typename T, typename R = void>
	class Polymorphic {
	public:
		inline static T read(SerializationCtx  &ctx, Buffer &buffer) {
			return T::read(ctx, buffer);
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, T const &value) {
			value.write(ctx, buffer);
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, Wrapper<T> const &value) {
			value->write(ctx, buffer);
		}
	};


	template<typename T>
	class Polymorphic<T, typename std::enable_if_t<std::is_integral<T>::value>> {
	public:
		inline static T read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_integral<T>();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, T const &value) {
			buffer.write_integral<T>(value);
		}
	};

	template<typename T>
	class Polymorphic<T, typename std::enable_if_t<std::is_floating_point<T>::value>> {
	public:
		inline static T read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_floating_point<T>();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, T const &value) {
			buffer.write_floating_point<T>(value);
		}
	};

	//class Polymorphic<int, void>;

	template<typename T>
	class Polymorphic<std::vector<T>> {
	public:
		inline static std::vector<T> read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_array<T>();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, std::vector<T> const &value) {
			buffer.write_array<T>(value);
		}
	};

	template<>
	class Polymorphic<bool> {
	public:
		inline static bool read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_bool();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, bool const &value) {
			buffer.write_bool(value);
		}
	};

	template<>
	class Polymorphic<wchar_t> {
	public:
		inline static wchar_t read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_char();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, bool const &value) {
			buffer.write_char(value);
		}
	};

	template<>
	class Polymorphic<std::wstring> {
	public:
		inline static std::wstring read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_wstring();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, std::wstring const &value) {
			buffer.write_wstring(value);
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, Wrapper<std::wstring> const &value) {
			buffer.write_wstring(*value);
		}
	};

	template<>
	class Polymorphic<DateTime> {
	public:
		inline static DateTime read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_date_time();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, DateTime const &value) {
			buffer.write_date_time(value);
		}
	};

	template<>
	class Polymorphic<Void> {
	public:
		inline static Void read(SerializationCtx  &ctx, Buffer &buffer) {
			return {};
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, Void const &value) {}
	};

	template<typename T>
	class Polymorphic<T, typename std::enable_if_t<util::is_base_of_v<RdReactiveBase, T>>> {
	public:
		inline static T read(SerializationCtx  &ctx, Buffer &buffer) {
			return T::read(ctx, buffer);
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, T const &value) {
			value.write(ctx, buffer);
		}
	};

	template<typename T>
	class Polymorphic<T, typename std::enable_if_t<util::is_enum_v<T>>> {
	public:
		inline static T read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_enum<T>();
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, T const &value) {
			buffer.write_enum<T>(value);
		}
	};

	template<typename T>
	class Polymorphic<optional<T>> {
	public:
		inline static optional<T> read(SerializationCtx  &ctx, Buffer &buffer) {
			return buffer.read_nullable<T>([&ctx, &buffer]() {
				return Polymorphic<T>::read(ctx, buffer);
			});
		}

		inline static void write(SerializationCtx  &ctx, Buffer &buffer, optional<T> const &value) {
			buffer.write_nullable<T>(value, [&ctx, &buffer](T const &v) {
				Polymorphic<T>::write(ctx, buffer, v);
			});
		}
	};

	template<typename T>
	class Polymorphic<Wrapper<T>> {
	public:
		inline static void write(SerializationCtx  &ctx, Buffer &buffer, Wrapper<T> const &value) {
			value->write(ctx, buffer);
		}
	};
}

extern template class rd::Polymorphic<int8_t>;
extern template class rd::Polymorphic<int16_t>;
extern template class rd::Polymorphic<int32_t>;
extern template class rd::Polymorphic<int64_t>;

#endif //RD_CPP_POLYMORPHIC_H
